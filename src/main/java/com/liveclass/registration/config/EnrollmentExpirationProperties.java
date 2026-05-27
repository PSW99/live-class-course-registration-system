package com.liveclass.registration.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** PENDING enrollment 자동 만료 정책. */
@ConfigurationProperties(prefix = "course-registration.enrollment.expiration")
public record EnrollmentExpirationProperties(
        Duration ttl,
        int batchSize,
        boolean schedulerEnabled
) {
    public EnrollmentExpirationProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
    }
}
