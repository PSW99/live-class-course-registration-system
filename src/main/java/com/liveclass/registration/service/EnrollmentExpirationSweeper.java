package com.liveclass.registration.service;

import com.liveclass.registration.config.EnrollmentExpirationProperties;
import com.liveclass.registration.repository.EnrollmentRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TTL 경과 PENDING 후보 ID 수집 + per-row 만료 위임. 자동 호출은 {@link EnrollmentExpirationTrigger}가
 * 담당하고 본 빈은 항상 등록되어 통합 테스트가 sweep을 직접 호출할 수 있다.
 *
 * sweep은 후보 ID만 readOnly로 수집하고 실제 만료는 {@link EnrollmentExpirationService}의
 * REQUIRES_NEW 트랜잭션에 위임한다 — 한 row 실패가 다음 row 처리에 영향 주지 않도록.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EnrollmentExpirationSweeper {

    private final EnrollmentRepository enrollmentRepository;
    private final EnrollmentExpirationService expirationService;
    private final EnrollmentExpirationProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime threshold = now.minus(properties.ttl());

        List<Long> candidates = enrollmentRepository.findExpiredPendingIds(
                threshold, PageRequest.of(0, properties.batchSize()));
        if (candidates.isEmpty()) {
            return;
        }

        int expired = 0;
        int skipped = 0;
        for (Long id : candidates) {
            try {
                expirationService.expireOne(id, now, threshold);
                expired++;
            } catch (RuntimeException e) {
                // 한 row의 락 타임아웃·동시성 충돌은 다음 row 처리에 영향 주지 않는다.
                skipped++;
                log.warn("PENDING 만료 실패 — enrollmentId={}", id, e);
            }
        }
        log.info("PENDING 만료 sweep 완료 — 후보={}, 만료={}, 스킵={}", candidates.size(), expired, skipped);
    }
}
