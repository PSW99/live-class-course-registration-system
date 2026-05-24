package com.liveclass.registration.service;

import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.global.exception.LockAcquisitionException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 수강 신청 임계 구역의 분산락 진입점. Redisson {@code RLock}으로 동시 요청을 직렬화한 뒤
 * {@code EnrollmentService}에 위임한다.
 *
 * 이 클래스에는 {@code @Transactional}을 부착하지 않는다. 같은 메서드 안에서 락 해제와 트랜잭션 커밋이
 * 함께 일어나면 {@code unlock → commit} 순서가 되어 락이 풀린 짧은 순간에 다음 요청이 미커밋 상태를 읽는다.
 * 빈을 분리해 {@code commit → unlock} 순서를 코드 구조로 강제한다.
 */
@Component
@RequiredArgsConstructor
public class EnrollmentLockFacade {

    private static final String LOCK_KEY_PREFIX = "enrollment:class:";
    private static final long WAIT_SEC = 5L;
    private static final long LEASE_SEC = 3L;

    private final RedissonClient redissonClient;
    private final EnrollmentService enrollmentService;

    public Enrollment enroll(long userId, long classId) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + classId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_SEC, LEASE_SEC, TimeUnit.SECONDS);
            if (!acquired) {
                throw new LockAcquisitionException(classId);
            }
            // 이 호출이 리턴되는 시점에 @Transactional 프록시가 commit을 완료한 상태.
            return enrollmentService.enroll(userId, classId);
        } catch (InterruptedException e) {
            // 상위 스레드(tomcat 워커)가 인터럽트 상태를 잃지 않도록 플래그 복구
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(classId, e);
        } finally {
            // isHeldByCurrentThread() 가드로 다른 스레드의 락을 푸는 사고와 IllegalMonitorStateException을 방지
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
