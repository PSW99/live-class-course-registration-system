package com.liveclass.registration.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간 소스 단일화 빈. 시간 의존 로직은 정적 {@code OffsetDateTime.now()}가 아닌
 * 본 빈을 주입받아 사용한다 — 테스트에서 {@code Clock.fixed(...)}로 오버라이드해
 * 시간 경계를 결정적으로 검증한다.
 *
 * 운영에서는 {@code Clock.systemUTC()} — 모든 시간 비교를 UTC 기준으로 일관 적용.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
