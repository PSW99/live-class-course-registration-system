package com.liveclass.registration.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.domain.WaitlistEntry;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.repository.WaitlistRepository;
import com.liveclass.registration.service.EnrollmentExpirationSweeper;
import com.liveclass.registration.service.EnrollmentExpirationService;
import com.liveclass.registration.support.PostgresRedisContainerSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
@Import(EnrollmentExpirationIntegrationTest.FixedClockConfig.class)
@DisplayName("PENDING enrollment 자동 만료 통합 테스트")
class EnrollmentExpirationIntegrationTest extends PostgresRedisContainerSupport {

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
    private long pendingUserId;
    private long waiterId;
    private long secondWaiterId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        creatorId = userRepository.saveAndFlush(new User("Creator", "creator-exp@example.com")).getId();
        pendingUserId = userRepository.saveAndFlush(new User("Pending", "pending-exp@example.com")).getId();
        waiterId = userRepository.saveAndFlush(new User("Waiter", "waiter-exp@example.com")).getId();
        secondWaiterId = userRepository.saveAndFlush(new User("Waiter2", "waiter2-exp@example.com")).getId();
    }

    @AfterEach
    void cleanUp() {
        waitlistRepository.deleteAllInBatch();
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("TTL 10분 초과 PENDING은 CANCELLED로 전이되고 current_count가 1 감소한다")
    void expireOne_pendingExpired_transitionsToCancelled() {
        long classId = createOpenClass(creatorId, 5, 1);
        long enrollmentId = insertPendingEnrollmentAt(pendingUserId, classId, FIXED_NOW.minusMinutes(15));

        expirationService.expireOne(enrollmentId, FIXED_NOW, THRESHOLD);

        Enrollment after = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(after.getCancelledAt()).isEqualTo(FIXED_NOW);
        assertThat(rawCurrentCount(classId)).isZero();
    }

    @Test
    @DisplayName("TTL 10분 미만 PENDING은 만료되지 않는다")
    void expireOne_pendingNotYetExpired_noop() {
        long classId = createOpenClass(creatorId, 5, 1);
        long enrollmentId = insertPendingEnrollmentAt(pendingUserId, classId, FIXED_NOW.minusMinutes(5));

        expirationService.expireOne(enrollmentId, FIXED_NOW, THRESHOLD);

        Enrollment after = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(rawCurrentCount(classId)).isEqualTo(1);
    }

    @Test
    @DisplayName("CONFIRMED enrollment는 created_at이 오래되어도 만료되지 않는다")
    void expireOne_confirmed_noop() {
        long classId = createOpenClass(creatorId, 5, 1);
        long enrollmentId = insertPendingEnrollmentAt(pendingUserId, classId, FIXED_NOW.minusHours(2));
        jdbcTemplate.update(
                "UPDATE enrollments SET status='CONFIRMED', confirmed_at=? WHERE id=?",
                Timestamp.from(FIXED_NOW.minusHours(1).toInstant()), enrollmentId);

        expirationService.expireOne(enrollmentId, FIXED_NOW, THRESHOLD);

        Enrollment after = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(rawCurrentCount(classId)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 CANCELLED인 enrollment에 expireOne을 호출해도 멱등 — current_count 변화 없음")
    void expireOne_alreadyCancelled_isIdempotent() {
        long classId = createOpenClass(creatorId, 5, 0);
        long enrollmentId = insertPendingEnrollmentAt(pendingUserId, classId, FIXED_NOW.minusMinutes(20));
        jdbcTemplate.update(
                "UPDATE enrollments SET status='CANCELLED', cancelled_at=? WHERE id=?",
                Timestamp.from(FIXED_NOW.minusMinutes(5).toInstant()), enrollmentId);

        expirationService.expireOne(enrollmentId, FIXED_NOW, THRESHOLD);

        assertThat(rawCurrentCount(classId)).isZero();
    }

    @Test
    @DisplayName("만료 시 자리가 비는 즉시 첫 대기자가 PENDING으로 승격되고 current_count는 capacity 유지")
    void expireOne_withWaiter_promotesFirst() {
        long classId = createClosedFullClass(creatorId, 1);
        long enrollmentId = insertPendingEnrollmentAt(pendingUserId, classId, FIXED_NOW.minusMinutes(15));
        insertWaitlistEntry(waiterId, classId);

        expirationService.expireOne(enrollmentId, FIXED_NOW, THRESHOLD);

        Enrollment expired = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);

        List<Enrollment> active = enrollmentRepository.findAll().stream()
                .filter(e -> e.getStatus() != EnrollmentStatus.CANCELLED).toList();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getUser().getId()).isEqualTo(waiterId);
        assertThat(active.get(0).getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(rawCurrentCount(classId)).isEqualTo(1);
        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("scheduler.sweep — TTL 경과 PENDING만 만료되고 미경과·CONFIRMED는 그대로")
    void sweep_processesOnlyExpiredPending() {
        long classId = createOpenClass(creatorId, 5, 3);
        long expiredId = insertPendingEnrollmentAt(pendingUserId, classId, FIXED_NOW.minusMinutes(15));
        long freshId = insertPendingEnrollmentAt(waiterId, classId, FIXED_NOW.minusMinutes(3));
        long confirmedId = insertPendingEnrollmentAt(secondWaiterId, classId, FIXED_NOW.minusHours(1));
        jdbcTemplate.update(
                "UPDATE enrollments SET status='CONFIRMED', confirmed_at=? WHERE id=?",
                Timestamp.from(FIXED_NOW.minusMinutes(50).toInstant()), confirmedId);

        sweeper.sweep();

        assertThat(enrollmentRepository.findById(expiredId).orElseThrow().getStatus())
                .isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(enrollmentRepository.findById(freshId).orElseThrow().getStatus())
                .isEqualTo(EnrollmentStatus.PENDING);
        assertThat(enrollmentRepository.findById(confirmedId).orElseThrow().getStatus())
                .isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(rawCurrentCount(classId)).isEqualTo(2);
    }

    @Test
    @DisplayName("scheduler.sweep — 여러 PENDING이 모두 만료되고 정원도 정확히 감소")
    void sweep_multipleExpired_allCancelledAndCapacityCorrect() {
        long classId = createOpenClass(creatorId, 10, 3);
        long a = insertPendingEnrollmentAt(pendingUserId, classId, FIXED_NOW.minusMinutes(15));
        long b = insertPendingEnrollmentAt(waiterId, classId, FIXED_NOW.minusMinutes(20));
        long c = insertPendingEnrollmentAt(secondWaiterId, classId, FIXED_NOW.minusMinutes(30));

        sweeper.sweep();

        assertThat(enrollmentRepository.findById(a).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(enrollmentRepository.findById(b).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(enrollmentRepository.findById(c).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(rawCurrentCount(classId)).isZero();
    }

    @Transactional
    long createOpenClass(long ownerId, int capacity, int currentCount) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner, "Expire Class", "demo",
                new BigDecimal("10000.00"), capacity,
                FIXED_TODAY.plusDays(10), FIXED_TODAY.plusDays(40));
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        for (int i = 0; i < currentCount; i++) {
            cls.incrementCurrentCount();
        }
        courseClassRepository.saveAndFlush(cls);
        return cls.getId();
    }

    @Transactional
    long createClosedFullClass(long ownerId, int capacity) {
        long classId = createOpenClass(ownerId, capacity, capacity);
        jdbcTemplate.update("UPDATE classes SET status='CLOSED' WHERE id=?", classId);
        CourseClass refreshed = courseClassRepository.findById(classId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ClassStatus.CLOSED);
        return classId;
    }

    @Transactional
    long insertPendingEnrollmentAt(long userId, long classId, OffsetDateTime createdAt) {
        User user = userRepository.findById(userId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        Enrollment e = enrollmentRepository.saveAndFlush(new Enrollment(user, cls));
        jdbcTemplate.update(
                "UPDATE enrollments SET created_at=? WHERE id=?",
                Timestamp.from(createdAt.toInstant()), e.getId());
        return e.getId();
    }

    @Transactional
    void insertWaitlistEntry(long userId, long classId) {
        User user = userRepository.findById(userId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        waitlistRepository.saveAndFlush(new WaitlistEntry(user, cls));
    }

    private int rawCurrentCount(long classId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT current_count FROM classes WHERE id=?", Integer.class, classId);
        assertThat(c).isNotNull();
        return c;
    }
}
