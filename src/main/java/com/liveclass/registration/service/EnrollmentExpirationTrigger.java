package com.liveclass.registration.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link EnrollmentExpirationSweeper#sweep()}의 자동 트리거. 트랜잭션 경계는 sweeper가 가지며
 * 본 빈은 호출만 한다 — self-call로 인한 @Transactional 미적용 회피.
 *
 * 통합 테스트에서는 scheduler-enabled=false로 본 빈 등록을 막고 sweeper를 직접 호출한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "course-registration.enrollment.expiration",
        name = "scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EnrollmentExpirationTrigger {

    private final EnrollmentExpirationSweeper sweeper;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void trigger() {
        sweeper.sweep();
    }
}
