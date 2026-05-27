package com.liveclass.registration.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.domain.WaitlistEntry;
import com.liveclass.registration.global.exception.DuplicateWaitlistException;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.repository.WaitlistRepository;
import com.liveclass.registration.service.EnrollmentService;
import com.liveclass.registration.service.WaitlistService;
import com.liveclass.registration.support.PostgresRedisContainerSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
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
@DisplayName("대기열 동시성 — class 비관적 락 + DB UNIQUE 이중 방어")
class WaitlistConcurrencyTest extends PostgresRedisContainerSupport {

    @Autowired
    WaitlistService waitlistService;

    @Autowired
    EnrollmentService enrollmentService;

    @Autowired
    CourseClassRepository courseClassRepository;

    @Autowired
    EnrollmentRepository enrollmentRepository;

    @Autowired
    WaitlistRepository waitlistRepository;

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
        waitlistRepository.deleteAllInBatch();
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("정원 1·CLOSED 강의에 서로 다른 50명이 동시 대기 등록하면 50건 모두 성공하고 position이 1~50으로 빠짐없이 분포한다")
    void concurrentJoin_50DifferentUsers_allSucceedWithUniquePositions() throws InterruptedException {
        long creatorId = createUser("creator-wl-conc1@example.com");
        long classId = createClosedFullClass(creatorId, 1);
        List<Long> learnerIds = createUsers(50, "wl-conc1");

        int threads = learnerIds.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        ConcurrentLinkedQueue<Integer> positions = new ConcurrentLinkedQueue<>();

        for (Long learnerId : learnerIds) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    var response = waitlistService.join(classId, learnerId);
                    positions.add(response.position());
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

        assertThat(success.get()).isEqualTo(50);
        assertThat(failure.get()).isZero();
        assertThat(waitlistRepository.countByCourseClassId(classId)).isEqualTo(50L);
        assertThat(positions).containsExactlyInAnyOrderElementsOf(intRange(1, 50));
    }

    @Test
    @DisplayName("동일 사용자가 정원 1·CLOSED 강의에 동시 5건 대기 등록을 보내면 정확히 1건만 성공하고 4건은 DUPLICATE_WAITLIST로 실패한다")
    void concurrentJoin_sameUser5Requests_exactlyOneSucceeds() throws InterruptedException {
        long creatorId = createUser("creator-wl-conc2@example.com");
        long classId = createClosedFullClass(creatorId, 1);
        long learnerId = createUser("wl-conc2-learner@example.com");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicateFailure = new AtomicInteger();
        AtomicInteger otherFailure = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    waitlistService.join(classId, learnerId);
                    success.incrementAndGet();
                } catch (DuplicateWaitlistException e) {
                    duplicateFailure.incrementAndGet();
                } catch (Exception e) {
                    otherFailure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        boolean completed = done.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(completed).as("모든 스레드가 30초 안에 종료되어야 한다").isTrue();

        assertThat(success.get()).isEqualTo(1);
        assertThat(duplicateFailure.get()).isEqualTo(threads - 1);
        assertThat(otherFailure.get()).isZero();
        assertThat(waitlistRepository.countByCourseClassId(classId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("정원 1·대기 1명 상태에서 서로 다른 두 enrollment를 동시 cancel하면 정확히 한 번만 승격되고 current_count는 0이 된다")
    void concurrentCancel_twoEnrollmentsOneWaiter_promotesExactlyOnce() throws InterruptedException {
        long creatorId = createUser("creator-wl-conc3@example.com");
        long classId = createOpenClassWithCapacity(creatorId, 2);
        long enrolledUserA = createUser("wl-conc3-a@example.com");
        long enrolledUserB = createUser("wl-conc3-b@example.com");
        long waiterId = createUser("wl-conc3-waiter@example.com");

        long enrollmentAId = persistConfirmedEnrollment(enrolledUserA, classId);
        long enrollmentBId = persistConfirmedEnrollment(enrolledUserB, classId);
        markClassClosedAndCount(classId, 2);

        persistWaitlistEntry(waiterId, classId);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        List<long[]> tasks = List.of(
                new long[]{enrollmentAId, enrolledUserA},
                new long[]{enrollmentBId, enrolledUserB}
        );
        for (long[] task : tasks) {
            long enrollmentId = task[0];
            long requesterId = task[1];
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    enrollmentService.cancel(enrollmentId, requesterId);
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
        boolean completed = done.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(completed).as("모든 스레드가 30초 안에 종료되어야 한다").isTrue();

        assertThat(success.get()).isEqualTo(2);
        assertThat(failure.get()).isZero();

        long currentCount = jdbcTemplate.queryForObject(
                "SELECT current_count FROM classes WHERE id = ?", Long.class, classId);
        assertThat(currentCount).isEqualTo(1L);

        assertThat(waitlistRepository.countByCourseClassId(classId)).isZero();

        List<Enrollment> activeEnrollments = enrollmentRepository.findAll().stream()
                .filter(e -> e.getCourseClass().getId().equals(classId))
                .filter(e -> e.getStatus() != EnrollmentStatus.CANCELLED)
                .toList();
        assertThat(activeEnrollments).hasSize(1);
        assertThat(activeEnrollments.get(0).getUser().getId()).isEqualTo(waiterId);
        assertThat(activeEnrollments.get(0).getStatus()).isEqualTo(EnrollmentStatus.PENDING);
    }

    @Test
    @DisplayName("정원 5·대기 3명 상태에서 5건의 enrollment를 동시 cancel하면 모두 성공하고 3건이 승격되며 current_count는 3이 된다")
    void concurrentCancel_fiveEnrollmentsThreeWaiters_promotesExactlyThree() throws InterruptedException {
        long creatorId = createUser("creator-wl-conc4@example.com");
        long classId = createOpenClassWithCapacity(creatorId, 5);

        List<Long> enrolledUserIds = createUsers(5, "wl-conc4-enrolled");
        List<Long> enrollmentIds = new ArrayList<>();
        for (Long userId : enrolledUserIds) {
            enrollmentIds.add(persistConfirmedEnrollment(userId, classId));
        }
        markClassClosedAndCount(classId, 5);

        List<Long> waiterIds = createUsers(3, "wl-conc4-waiter");
        for (Long waiterId : waiterIds) {
            persistWaitlistEntry(waiterId, classId);
        }

        int threads = enrollmentIds.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            long enrollmentId = enrollmentIds.get(i);
            long requesterId = enrolledUserIds.get(i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    enrollmentService.cancel(enrollmentId, requesterId);
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

        assertThat(success.get()).isEqualTo(5);
        assertThat(failure.get()).isZero();

        long currentCount = jdbcTemplate.queryForObject(
                "SELECT current_count FROM classes WHERE id = ?", Long.class, classId);
        assertThat(currentCount).isEqualTo(3L);

        assertThat(waitlistRepository.countByCourseClassId(classId)).isZero();

        long activeEnrollmentCount = enrollmentRepository.findAll().stream()
                .filter(e -> e.getCourseClass().getId().equals(classId))
                .filter(e -> e.getStatus() != EnrollmentStatus.CANCELLED)
                .count();
        assertThat(activeEnrollmentCount).isEqualTo(3);
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

    private long createOpenClassWithCapacity(long ownerId, int capacity) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                "Concurrency Waitlist Class",
                "concurrency",
                new BigDecimal("50000.00"),
                capacity,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30)
        );
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        courseClassRepository.saveAndFlush(cls);
        return cls.getId();
    }

    private long createClosedFullClass(long ownerId, int capacity) {
        long classId = createOpenClassWithCapacity(ownerId, capacity);
        jdbcTemplate.update(
                "UPDATE classes SET current_count = ?, status = 'CLOSED' WHERE id = ?",
                capacity, classId);
        CourseClass refreshed = courseClassRepository.findById(classId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ClassStatus.CLOSED);
        assertThat(refreshed.getCurrentCount()).isEqualTo(capacity);
        return classId;
    }

    private void markClassClosedAndCount(long classId, int count) {
        jdbcTemplate.update(
                "UPDATE classes SET current_count = ?, status = 'CLOSED' WHERE id = ?",
                count, classId);
        CourseClass refreshed = courseClassRepository.findById(classId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ClassStatus.CLOSED);
        assertThat(refreshed.getCurrentCount()).isEqualTo(count);
    }

    private long persistConfirmedEnrollment(long userId, long classId) {
        User user = userRepository.findById(userId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        Enrollment enrollment = enrollmentRepository.saveAndFlush(new Enrollment(user, cls));
        long enrollmentId = enrollment.getId();
        jdbcTemplate.update(
                "UPDATE enrollments SET status = 'CONFIRMED', confirmed_at = now() WHERE id = ?",
                enrollmentId);
        return enrollmentId;
    }

    private void persistWaitlistEntry(long userId, long classId) {
        User user = userRepository.findById(userId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        waitlistRepository.saveAndFlush(new WaitlistEntry(user, cls));
    }

    private static List<Integer> intRange(int fromInclusive, int toInclusive) {
        List<Integer> list = new ArrayList<>(toInclusive - fromInclusive + 1);
        for (int i = fromInclusive; i <= toInclusive; i++) {
            list.add(i);
        }
        return list;
    }
}
