package com.liveclass.registration.service;

import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.domain.WaitlistEntry;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.repository.WaitlistRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자리가 비는 즉시 첫 대기자를 PENDING으로 승격. cancel과 자동 만료 양쪽에서 동일 헬퍼를 호출한다.
 *
 * 호출 전제 — 호출자가 이미 class row 락을 보유 중이고 currentCount를 한 자리 비워둔 상태.
 * Propagation.MANDATORY로 외부 트랜잭션 없이 호출되면 IllegalTransactionStateException으로 즉시 실패.
 */
@Service
@RequiredArgsConstructor
public class WaitlistPromotionService {

    private final WaitlistRepository waitlistRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void promoteFirstIfAny(CourseClass cls) {
        List<WaitlistEntry> first = waitlistRepository.findFirstByClassIdForUpdate(
                cls.getId(), PageRequest.of(0, 1));
        if (first.isEmpty()) {
            return;
        }
        WaitlistEntry entry = first.get(0);

        Long userId = entry.getUser().getId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "waitlist user 정합성 깨짐: waitlistId=" + entry.getId()));

        Enrollment promoted = new Enrollment(user, cls);
        cls.incrementCurrentCount();
        try {
            enrollmentRepository.saveAndFlush(promoted);
        } catch (DataIntegrityViolationException e) {
            // join 단계의 ACTIVE_ENROLLMENT_EXISTS 검증이 막았어야 정상.
            throw new IllegalStateException(
                    "waitlist 승격 중 활성 enrollment 중복 — user=" + userId + ", class=" + cls.getId(), e);
        }
        waitlistRepository.delete(entry);
    }
}
