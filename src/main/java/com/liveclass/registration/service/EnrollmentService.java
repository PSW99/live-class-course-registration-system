package com.liveclass.registration.service;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.global.exception.ClassNotOpenException;
import com.liveclass.registration.global.exception.DuplicateEnrollmentException;
import com.liveclass.registration.global.exception.NotFoundException;
import com.liveclass.registration.global.exception.SelfEnrollmentForbiddenException;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
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
        // LAZY proxy의 getId()는 초기화 없이 식별자만 꺼낸다 — Long.equals로 primitive 비교 함정도 회피
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
}
