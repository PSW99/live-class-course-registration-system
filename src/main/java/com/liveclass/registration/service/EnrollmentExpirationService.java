package com.liveclass.registration.service;

import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단일 PENDING enrollment의 만료를 격리된 트랜잭션으로 처리. REQUIRES_NEW로 호출자(scheduler)에
 * 트랜잭션이 없거나, 한 row 실패가 다음 row 처리를 막지 않도록 한다.
 *
 * 락 순서는 enrollment → class → (promote 안의) waitlist — 사용자 cancel과 동일.
 */
@Service
@RequiredArgsConstructor
public class EnrollmentExpirationService {

    private final EnrollmentRepository enrollmentRepository;
    private final CourseClassRepository courseClassRepository;
    private final WaitlistPromotionService waitlistPromotionService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireOne(long enrollmentId, OffsetDateTime now, OffsetDateTime threshold) {
        Enrollment enrollment = enrollmentRepository.findByIdForUpdate(enrollmentId)
                .orElse(null);
        if (enrollment == null) {
            return;
        }

        // stale guard — 락 획득 대기 중 사용자가 cancel/confirm 했거나 다른 인스턴스가 먼저 만료시킨 경우.
        if (enrollment.getStatus() != EnrollmentStatus.PENDING
                || enrollment.getCreatedAt().isAfter(threshold)) {
            return;
        }

        Long classId = enrollment.getCourseClass().getId();
        CourseClass cls = courseClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new IllegalStateException(
                        "만료 처리 중 class 정합성 깨짐: enrollmentId=" + enrollmentId));

        enrollment.cancel(now);
        cls.decrementCurrentCount();
        waitlistPromotionService.promoteFirstIfAny(cls);
    }
}
