package com.liveclass.registration.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.global.exception.InvalidStatusTransitionException;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.repository.WaitlistRepository;
import com.liveclass.registration.service.EnrollmentExpirationSweeper;
import com.liveclass.registration.service.EnrollmentExpirationService;
import com.liveclass.registration.service.EnrollmentService;
import com.liveclass.registration.support.PostgresRedisContainerSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "course-registration.enrollment.expiration.scheduler-enabled=false",
        "course-registration.enrollment.expiration.ttl=PT10M",
        "course-registration.enrollment.expiration.batch-size=500"
})
@Import(EnrollmentExpirationConcurrencyTest.FixedClockConfig.class)
@DisplayName("PENDING 자동 만료 동시성 — 사용자 흐름과의 race 검증")
class EnrollmentExpirationConcurrencyTest extends PostgresRedisContainerSupport {

    private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-05-27T10:00:00Z");
    private static final OffsetDateTime THRESHOLD = FIXED_NOW.minusMinutes(10);
    private static final LocalDate FIXED_TODAY = FIXED_NOW.toLocalDate();

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public Clock fixedClock() {
            return Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    EnrollmentExpirationService expirationService;
    @Autowired
    EnrollmentExpirationSweeper sweeper;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CourseClassRepository courseClassRepository;
    @Autowired
    EnrollmentRepository enrollmentRepository;
    @Autowired
    WaitlistRepository waitlistRepository;
    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private long creatorId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        creatorId = userRepository.saveAndFlush(new User("Creator", "creator-exp-conc@example.com")).getId();
    }

    @AfterEach
    void cleanUp() {
        waitlistRepository.deleteAllInBatch();
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("동일 enrollment에 만료와 사용자 cancel이 동시 — 한쪽만 정원 차감, 최종 status는 CANCELLED")
    void expire_and_userCancel_onSameEnrollment_decrementsOnce() throws Exception {
        long userId = userRepository.saveAndFlush(new User("U", "u-race1@example.com")).getId();
        long classId = createOpenClass(creatorId, 5, 1);
        long enrollmentId = insertPendingAt(userId, classId, FIXED_NOW.minusMinutes(15));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        pool.submit(() -> {
            try {
                start.await();
                expirationService.expireOne(enrollmentId, FIXED_NOW, THRESHOLD);
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                enrollmentService.cancel(enrollmentId, userId);
            } catch (InvalidStatusTransitionException ignored) {
                // 한쪽이 먼저 CANCELLED로 만들면 두 번째가 InvalidStatusTransition 던짐 — 정상
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors).isEmpty();
        Enrollment after = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(rawCurrentCount(classId)).isZero();
    }

    @Test
    @DisplayName("만료와 사용자 confirm이 동시 — confirm 먼저면 CONFIRMED 유지, 만료 먼저면 confirm 실패")
    void expire_and_userConfirm_onSameEnrollment() throws Exception {
        long userId = userRepository.saveAndFlush(new User("U", "u-race2@example.com")).getId();
        long classId = createOpenClass(creatorId, 5, 1);
        long enrollmentId = insertPendingAt(userId, classId, FIXED_NOW.minusMinutes(15));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        pool.submit(() -> {
            try {
                start.await();
                expirationService.expireOne(enrollmentId, FIXED_NOW, THRESHOLD);
            } catch (Throwable t) {
                unexpected.add(t);
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                enrollmentService.confirm(enrollmentId, userId);
            } catch (InvalidStatusTransitionException ignored) {
                // 만료가 먼저면 CANCELLED → confirm InvalidStatusTransition. 정상.
            } catch (Throwable t) {
                unexpected.add(t);
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        Enrollment after = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(after.getStatus()).isIn(EnrollmentStatus.CONFIRMED, EnrollmentStatus.CANCELLED);
        if (after.getStatus() == EnrollmentStatus.CONFIRMED) {
            assertThat(rawCurrentCount(classId)).isEqualTo(1);
        } else {
            assertThat(rawCurrentCount(classId)).isZero();
        }
    }

    @Test
    @DisplayName("다중 PENDING 동시 만료 — 모두 CANCELLED, current_count는 정확히 만료 수만큼 감소")
    void sweep_multipleExpired_capacityAccurate() throws Exception {
        int n = 10;
        long classId = createOpenClass(creatorId, 20, n);
        List<Long> enrollmentIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long uid = userRepository.saveAndFlush(new User("U" + i, "u-sweep" + i + "@example.com")).getId();
            enrollmentIds.add(insertPendingAt(uid, classId, FIXED_NOW.minusMinutes(15 + i)));
        }

        sweeper.sweep();

        for (Long id : enrollmentIds) {
            assertThat(enrollmentRepository.findById(id).orElseThrow().getStatus())
                    .isEqualTo(EnrollmentStatus.CANCELLED);
        }
        assertThat(rawCurrentCount(classId)).isZero();
    }

    @Transactional
    long createOpenClass(long ownerId, int capacity, int currentCount) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner, "Race Class", "demo",
                new BigDecimal("10000.00"), capacity,
                FIXED_TODAY.plusDays(10), FIXED_TODAY.plusDays(40));
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        for (int i = 0; i < currentCount; i++) {
            cls.incrementCurrentCount();
        }
        courseClassRepository.saveAndFlush(cls);
        assertThat(cls.getStatus()).isEqualTo(ClassStatus.OPEN);
        return cls.getId();
    }

    @Transactional
    long insertPendingAt(long userId, long classId, OffsetDateTime createdAt) {
        User user = userRepository.findById(userId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        Enrollment e = enrollmentRepository.saveAndFlush(new Enrollment(user, cls));
        jdbcTemplate.update(
                "UPDATE enrollments SET created_at=? WHERE id=?",
                Timestamp.from(createdAt.toInstant()), e.getId());
        return e.getId();
    }

    private int rawCurrentCount(long classId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT current_count FROM classes WHERE id=?", Integer.class, classId);
        assertThat(c).isNotNull();
        return c;
    }
}
