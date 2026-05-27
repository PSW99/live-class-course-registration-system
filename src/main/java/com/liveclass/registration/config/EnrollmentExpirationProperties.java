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
}
