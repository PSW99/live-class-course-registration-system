package com.liveclass.registration.service;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.global.exception.CancelPeriodExpiredException;
import com.liveclass.registration.global.exception.ClassAlreadyStartedException;
import com.liveclass.registration.global.exception.ClassNotOpenException;
import com.liveclass.registration.global.exception.DuplicateEnrollmentException;
import com.liveclass.registration.global.exception.ForbiddenAccessException;
import com.liveclass.registration.global.exception.InvalidStatusTransitionException;
import com.liveclass.registration.global.exception.NotFoundException;
import com.liveclass.registration.global.exception.SelfEnrollmentForbiddenException;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수강 신청 트랜잭션. 비관적 락이 잡힌 강의 row 위에서 모든 비즈니스 검증·정원 차감·신청 insert를 수행한다.
 *
 * 모든 검증은 락 안에서만 수행한다 — 락 외부에서 사전 검사하면 TOCTOU 갭이 노출된다.
 * 본 서비스는 {@code EnrollmentLockFacade}가 분산락을 획득한 뒤에만 호출된다.
 */
@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final CourseClassRepository courseClassRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * 검증 순서:
     *   1. 강의 존재 + 비관적 락 획득 (한 쿼리)
     *   2. OPEN 상태 확인
     *   3. 본인 강의 본인 신청 차단
     *   4. 정원 증가 (capacity 초과 시 도메인 메서드가 throw)
     *   5. capacity 도달 시 자동 마감 (Integer.equals — == 박싱 캐시 함정 회피)
     *   6. 신청 즉시 flush — DB 중복 위반을 같은 메서드 안에서 도메인 예외로 변환
     */
    @Transactional
    public Enrollment enroll(long userId, long classId) {
        CourseClass cls = courseClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new NotFoundException("class", classId));

        if (cls.getStatus() != ClassStatus.OPEN) {
            throw new ClassNotOpenException(cls.getId(), cls.getStatus());
        }
        if (cls.getCreator().getId().equals(userId)) {
            throw new SelfEnrollmentForbiddenException(userId, classId);
        }

        cls.incrementCurrentCount();
        if (cls.getCurrentCount().equals(cls.getCapacity())) {
            cls.close();
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("user", userId));
        Enrollment enrollment = new Enrollment(user, cls);
        try {
            // saveAndFlush로 즉시 INSERT를 발사해야 unique violation을 이 메서드 안에서 잡을 수 있다.
            // 기본 save는 commit 시점까지 flush를 미뤄 LockFacade try-catch 밖에서 예외가 터진다.
            return enrollmentRepository.saveAndFlush(enrollment);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEnrollmentException(userId, classId, e);
        }
    }

    /**
     * 결제 확정. PENDING enrollment를 CONFIRMED로 단방향 전이한다.
     *
     * 검증 순서는 404 → 403 → 400 고정. 상태 전이 가드는 도메인 메서드가 담당한다.
     *
     * 본 시스템은 자기 자신의 enrollment row 하나만 수정하므로 분산락·비관적 락이 필요 없다.
     * 같은 enrollment에 대한 동시 confirm은 본인 더블 탭뿐이며 last-write-wins로 사용자 영향이 없다.
     */
    @Transactional
    public Enrollment confirm(long enrollmentId, long requesterId) {
        Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new NotFoundException("enrollment", enrollmentId));
        assertOwner(enrollment, requesterId);
        enrollment.confirm();
        return enrollment;
    }

    /**
     * 수강 취소. PENDING/CONFIRMED enrollment를 CANCELLED로 전이하고 강의 정원을 즉시 반환한다.
     *
     * 검증 순서는 404 → 403 → 400 고정:
     *   1. enrollment 존재 확인 (404)
     *   2. owner 확인 (403)
     *   3. 이미 CANCELLED 차단 (400 INVALID_STATUS_TRANSITION)
     *   4. CONFIRMED인 경우 BR-08 시간 검증 (400 CANCEL_PERIOD_EXPIRED / CLASS_ALREADY_STARTED)
     *
     * 시간 비교는 트랜잭션 진입 직후 {@code clock}으로 한 번만 캡처해 검증과
     * {@code cancelledAt} set 양쪽에 같은 값을 사용한다 — 트랜잭션 내 시간 일관성.
     *
     * 강의 row는 비관적 락으로 잠근 인스턴스에 대해서만 {@code decrementCurrentCount()}와
     * {@code getStartDate()} 비교를 수행한다 — concurrent cancel·enroll-cancel race 차단.
     * enrollment의 LAZY proxy {@code getCourseClass()}는 락이 보장되지 않으므로 사용하지 않는다.
     */
    @Transactional
    public Enrollment cancel(long enrollmentId, long requesterId) {
        Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new NotFoundException("enrollment", enrollmentId));

        assertOwner(enrollment, requesterId);

        if (enrollment.getStatus() == EnrollmentStatus.CANCELLED) {
            throw new InvalidStatusTransitionException(
                    enrollment.getId(), EnrollmentStatus.CANCELLED, EnrollmentStatus.CANCELLED);
        }

        // 시간은 한 번만 캡처 — 검증과 set 양쪽에 같은 값을 사용
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);

        Long classId = enrollment.getCourseClass().getId();
        CourseClass cls = courseClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new NotFoundException("class", classId));

        if (enrollment.getStatus() == EnrollmentStatus.CONFIRMED) {
            // isAfter는 strict greater-than — confirmedAt + 7일 == now (정확히 7일) 시점은 throw
            if (!enrollment.getConfirmedAt().plusDays(7).isAfter(now)) {
                throw new CancelPeriodExpiredException(enrollment.getId());
            }
            // startDate가 오늘이면 throw — "오늘 시작하는 강의는 이미 시작"
            if (!cls.getStartDate().isAfter(today)) {
                throw new ClassAlreadyStartedException(enrollment.getId());
            }
        }

        enrollment.cancel(now);
        cls.decrementCurrentCount();
        return enrollment;
    }

    private void assertOwner(Enrollment enrollment, long requesterId) {
        if (!enrollment.getUser().getId().equals(requesterId)) {
            throw new ForbiddenAccessException("enrollment", enrollment.getId(), requesterId);
        }
    }
}
