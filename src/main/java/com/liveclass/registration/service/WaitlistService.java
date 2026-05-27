package com.liveclass.registration.service;

import com.liveclass.registration.controller.dto.WaitlistResponse;
import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.domain.WaitlistEntry;
import com.liveclass.registration.global.exception.ActiveEnrollmentExistsException;
import com.liveclass.registration.global.exception.CapacityAvailableException;
import com.liveclass.registration.global.exception.ClassNotOpenException;
import com.liveclass.registration.global.exception.DuplicateWaitlistException;
import com.liveclass.registration.global.exception.NotFoundException;
import com.liveclass.registration.global.exception.SelfWaitlistForbiddenException;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.repository.WaitlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대기열 등록·이탈·조회 서비스.
 *
 * 등록은 class row의 비관적 락 안에서 모든 검증을 수행해 stale read를 차단한다.
 * 분산락은 사용하지 않는다 — 대기 등록은 정원이 가득 차야 호출 가능하므로 contention이
 * enroll보다 낮고, 새 row INSERT만 하므로 lost update 자원이 없다.
 *
 * 자동 승격은 본 서비스가 아닌 {@code EnrollmentService.cancel()}에서 직접 수행한다 —
 * 같은 트랜잭션·같은 class 락을 재사용해 순환 의존을 회피한다.
 */
@Service
@RequiredArgsConstructor
public class WaitlistService {

    private final CourseClassRepository courseClassRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final WaitlistRepository waitlistRepository;
    private final UserRepository userRepository;

    /**
     * 대기열 등록.
     *
     * 검증 순서 (404 → 400 → 400 → 400 → 409 → 409 → DB UNIQUE):
     *   1. 강의 존재 + 비관적 락 획득 — 404 NOT_FOUND
     *   2. DRAFT 거부 — 400 CLASS_NOT_OPEN (CLOSED는 정원 가득 분기에서 자연 통과)
     *   3. 본인 강의 대기 거부 — 400 SELF_WAITLIST_FORBIDDEN
     *   4. 정원 남음 — 400 CAPACITY_AVAILABLE
     *   5. 활성 enrollment 존재 — 409 ACTIVE_ENROLLMENT_EXISTS
     *   6. 중복 대기 1차 필터 — 409 DUPLICATE_WAITLIST
     *   7. INSERT — saveAndFlush + DataIntegrityViolationException → DuplicateWaitlistException 변환
     *
     * 모든 검증이 같은 class 락 안에서 실행되므로 cancel/enroll의 동시 진행 사이에 stale 카운트를
     * 보지 않는다.
     */
    @Transactional
    public WaitlistResponse join(long classId, long requesterId) {
        CourseClass cls = courseClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new NotFoundException("class", classId));

        if (cls.getStatus() == ClassStatus.DRAFT) {
            throw new ClassNotOpenException(cls.getId(), cls.getStatus());
        }
        if (cls.getCreator().getId().equals(requesterId)) {
            throw new SelfWaitlistForbiddenException(requesterId, classId);
        }
        if (cls.getCurrentCount() < cls.getCapacity()) {
            throw new CapacityAvailableException(classId);
        }
        if (enrollmentRepository.existsActiveByUserIdAndClassId(requesterId, classId)) {
            throw new ActiveEnrollmentExistsException(requesterId, classId);
        }
        if (waitlistRepository.existsByCourseClassIdAndUserId(classId, requesterId)) {
            throw new DuplicateWaitlistException(requesterId, classId);
        }

        User user = userRepository.findById(requesterId)
                .orElseThrow(() -> new NotFoundException("user", requesterId));
        WaitlistEntry entry = new WaitlistEntry(user, cls);
        WaitlistEntry saved;
        try {
            // saveAndFlush로 즉시 INSERT를 발사해야 unique violation을 이 메서드 안에서 잡을 수 있다.
            // 기본 save는 commit 시점까지 flush를 미뤄 컨트롤러 try-catch 밖에서 예외가 터진다.
            saved = waitlistRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateWaitlistException(requesterId, classId, e);
        }

        // 방금 INSERT 한 row가 FIFO 마지막 위치이므로 자기 포함 총 수가 곧 자신의 position.
        int position = (int) waitlistRepository.countByCourseClassId(classId);
        return WaitlistResponse.from(saved, position);
    }

    /** 본인 대기 이탈. promote와 동일 row 경합을 피하려 비관적 락으로 조회 후 삭제. */
    @Transactional
    public void leave(long classId, long requesterId) {
        if (!courseClassRepository.existsById(classId)) {
            throw new NotFoundException("class", classId);
        }
        WaitlistEntry entry = waitlistRepository.findByCourseClassIdAndUserIdForUpdate(classId, requesterId)
                .orElseThrow(() -> new NotFoundException("waitlist", classId));
        waitlistRepository.delete(entry);
    }

    /**
     * 본인 대기 순번 조회. {@code (createdAt ASC, id ASC)} 정렬 기준의 1-base 순번을 반환한다.
     */
    @Transactional(readOnly = true)
    public WaitlistResponse findMine(long classId, long requesterId) {
        if (!courseClassRepository.existsById(classId)) {
            throw new NotFoundException("class", classId);
        }
        WaitlistEntry entry = waitlistRepository.findByCourseClassIdAndUserId(classId, requesterId)
                .orElseThrow(() -> new NotFoundException("waitlist", classId));
        int position = (int) waitlistRepository.countAheadOf(classId, entry.getCreatedAt(), entry.getId()) + 1;
        return WaitlistResponse.from(entry, position);
    }
}
