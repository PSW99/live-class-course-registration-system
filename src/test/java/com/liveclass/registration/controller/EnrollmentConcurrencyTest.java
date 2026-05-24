package com.liveclass.registration.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.service.EnrollmentLockFacade;
import com.liveclass.registration.support.PostgresRedisContainerSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("수강 신청 동시성 — Redisson + 비관적 락 이중 방어")
class EnrollmentConcurrencyTest extends PostgresRedisContainerSupport {

    @Autowired
    EnrollmentLockFacade enrollmentLockFacade;

    @Autowired
    CourseClassRepository courseClassRepository;

    @Autowired
    EnrollmentRepository enrollmentRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanUp() {
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("정원 1인 강의에 100명이 동시 신청하면 정확히 1명만 성공하고 강의가 CLOSED로 전이한다")
    void capacity1_with100Requests_resultsInExactly1Success() throws InterruptedException {
        long creatorId = createUser("creator-c1@example.com");
        long classId = createOpenClass(creatorId, 1);
        List<Long> learnerIds = createUsers(100, "c1-learner");

        ConcurrencyResult result = runConcurrentEnroll(learnerIds, classId);

        assertThat(result.success.get()).isEqualTo(1);
        assertThat(result.failure.get()).isEqualTo(99);
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        assertThat(cls.getCurrentCount()).isEqualTo(1);
        assertThat(cls.getStatus()).isEqualTo(ClassStatus.CLOSED);
        assertThat(activeEnrollmentCount(classId)).isEqualTo(1);
    }

    @Test
    @DisplayName("정원 5인 강의에 100명이 동시 신청하면 정확히 5명만 성공하고 강의가 CLOSED로 전이한다")
    void capacity5_with100Requests_resultsInExactly5Success() throws InterruptedException {
        long creatorId = createUser("creator-c5@example.com");
        long classId = createOpenClass(creatorId, 5);
        List<Long> learnerIds = createUsers(100, "c5-learner");

        ConcurrencyResult result = runConcurrentEnroll(learnerIds, classId);

        assertThat(result.success.get()).isEqualTo(5);
        assertThat(result.failure.get()).isEqualTo(95);
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        assertThat(cls.getCurrentCount()).isEqualTo(5);
        assertThat(cls.getStatus()).isEqualTo(ClassStatus.CLOSED);
        assertThat(activeEnrollmentCount(classId)).isEqualTo(5);
    }

    @Test
    @DisplayName("동일 사용자가 같은 강의에 동시 2건을 신청하면 1건만 성공한다")
    void sameUser_with2ConcurrentRequests_resultsInExactly1Success() throws InterruptedException {
        long creatorId = createUser("creator-dup@example.com");
        long classId = createOpenClass(creatorId, 10);
        long learnerId = createUser("dup-learner@example.com");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    enrollmentLockFacade.enroll(learnerId, classId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(failure.get()).isEqualTo(1);
        assertThat(activeEnrollmentCount(classId)).isEqualTo(1);
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        assertThat(cls.getCurrentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("락 이중 방어가 적용된 경로에서 정원 1·동시 100건도 lost update 없이 1건만 성공한다")
    void dualDefense_on_protectsAgainstLostUpdate() throws InterruptedException {
        long creatorId = createUser("creator-defense@example.com");
        long classId = createOpenClass(creatorId, 1);
        List<Long> learnerIds = createUsers(100, "defense-learner");

        ConcurrencyResult result = runConcurrentEnroll(learnerIds, classId);

        assertThat(result.success.get()).isEqualTo(1);
        assertThat(result.failure.get()).isEqualTo(99);
        long currentCount = jdbcTemplate.queryForObject(
                "SELECT current_count FROM classes WHERE id = ?", Long.class, classId);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM classes WHERE id = ?", String.class, classId);
        assertThat(currentCount).isEqualTo(1L);
        assertThat(status).isEqualTo("CLOSED");
        assertThat(activeEnrollmentCount(classId)).isEqualTo(1);
    }

    private ConcurrencyResult runConcurrentEnroll(List<Long> learnerIds, long classId) throws InterruptedException {
        int threads = learnerIds.size();
        // 풀 크기 < 작업 수면 ready latch가 영원히 0에 도달하지 못해 데드락
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (Long learnerId : learnerIds) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    enrollmentLockFacade.enroll(learnerId, classId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        boolean completed = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(completed).as("모든 스레드가 60초 안에 종료되어야 한다").isTrue();
        return new ConcurrencyResult(success, failure);
    }

    private long activeEnrollmentCount(long classId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enrollments WHERE class_id = ? AND status <> 'CANCELLED'",
                Long.class, classId);
        return count == null ? 0L : count;
    }

    private long createUser(String email) {
        return userRepository.saveAndFlush(new User("User-" + email, email)).getId();
    }

    private List<Long> createUsers(int count, String prefix) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            User u = userRepository.saveAndFlush(
                    new User(prefix + "-" + i, prefix + "-" + i + "@example.com"));
            ids.add(u.getId());
        }
        return ids;
    }

    private long createOpenClass(long ownerId, int capacity) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                "Concurrency Class",
                "concurrency",
                new BigDecimal("50000.00"),
                capacity,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
        );
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        courseClassRepository.saveAndFlush(cls);
        return cls.getId();
    }

    private record ConcurrencyResult(AtomicInteger success, AtomicInteger failure) {
    }
}
