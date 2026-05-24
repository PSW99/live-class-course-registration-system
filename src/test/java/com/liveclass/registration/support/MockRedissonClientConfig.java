package com.liveclass.registration.support;

import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Redis 없이 도는 통합 테스트가 {@code RedissonClient}에 의존하는 빈
 * (예: {@code EnrollmentLockFacade})까지 함께 로딩될 때만 컨텍스트가 깨지지 않도록
 * 제공하는 mock 빈. 분산락 동작은 본 컨피그로 검증하지 않는다.
 */
@TestConfiguration
public class MockRedissonClientConfig {

    @Bean
    public RedissonClient redissonClient() {
        return Mockito.mock(RedissonClient.class);
    }
}
