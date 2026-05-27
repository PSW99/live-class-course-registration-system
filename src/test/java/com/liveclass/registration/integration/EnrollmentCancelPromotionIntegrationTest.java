package com.liveclass.registration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.liveclass.registration.support.PostgresRedisContainerSupport;
import java.math.BigDecimal;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(EnrollmentCancelPromotionIntegrationTest.FixedClockConfig.class)
@DisplayName("POST /api/enrollments/{id}/cancel — 대기열 자동 승격 통합 테스트")
class EnrollmentCancelPromotionIntegrationTest extends PostgresRedisContainerSupport {

    private static final OffsetDateTime FIXED_NOW =
            OffsetDateTime.parse("2026-05-27T10:00:00Z");
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
    MockMvc mockMvc;

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
    private long enrolledUserId;
    private long firstWaiterId;
    private long secondWaiterId;
    private long thirdWaiterId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        User creator = userRepository.saveAndFlush(new User("Creator", "creator-promote@example.com"));
        User enrolled = userRepository.saveAndFlush(new User("Enrolled", "enrolled-promote@example.com"));
        User first = userRepository.saveAndFlush(new User("First", "first-promote@example.com"));
        User second = userRepository.saveAndFlush(new User("Second", "second-promote@example.com"));
        User third = userRepository.saveAndFlush(new User("Third", "third-promote@example.com"));
        creatorId = creator.getId();
        enrolledUserId = enrolled.getId();
        firstWaiterId = first.getId();
        secondWaiterId = second.getId();
        thirdWaiterId = third.getId();
    }

    @AfterEach
    void cleanUp() {
        waitlistRepository.deleteAllInBatch();
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("대기 없는 강의에서 cancel 시 current_count는 0이 되고 CLOSED 상태는 그대로 유지된다")
    void cancel_withoutWaiters_decrementsCountAndKeepsClosed() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        long enrollmentId = persistPendingEnrollment(enrolledUserId, classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", enrolledUserId))
                .andExpect(status().isOk());

        assertThat(rawCurrentCount(classId)).isZero();
        assertThat(rawStatus(classId)).isEqualTo("CLOSED");
        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("대기 1명이 있는 강의에서 cancel 시 첫 대기자가 PENDING으로 승격되고 current_count는 capacity 유지된다")
    void cancel_withOneWaiter_promotesFirstAndKeepsCapacity() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        long enrollmentId = persistPendingEnrollment(enrolledUserId, classId);
        persistWaitlistEntry(firstWaiterId, classId);
        assertThat(waitlistRepository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", enrolledUserId))
                .andExpect(status().isOk());

        assertThat(rawCurrentCount(classId)).isEqualTo(1);
        assertThat(rawStatus(classId)).isEqualTo("CLOSED");
        assertThat(waitlistRepository.count()).isZero();

        List<Enrollment> activeEnrollments = enrollmentRepository.findAll().stream()
                .filter(e -> e.getStatus() != EnrollmentStatus.CANCELLED)
                .toList();
        assertThat(activeEnrollments).hasSize(1);
        Enrollment promoted = activeEnrollments.get(0);
        assertThat(promoted.getUser().getId()).isEqualTo(firstWaiterId);
        assertThat(promoted.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(promoted.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("대기 3명이 있는 강의에서 cancel 시 첫 대기자만 승격되고 나머지 2명은 대기에 남는다")
    void cancel_withThreeWaiters_promotesOnlyFirst() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        long enrollmentId = persistPendingEnrollment(enrolledUserId, classId);
        persistWaitlistEntry(firstWaiterId, classId);
        persistWaitlistEntry(secondWaiterId, classId);
        persistWaitlistEntry(thirdWaiterId, classId);
        assertThat(waitlistRepository.count()).isEqualTo(3);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", enrolledUserId))
                .andExpect(status().isOk());

        assertThat(rawCurrentCount(classId)).isEqualTo(1);
        assertThat(rawStatus(classId)).isEqualTo("CLOSED");
        assertThat(waitlistRepository.count()).isEqualTo(2);

        List<Long> remainingUserIds = waitlistRepository.findAll().stream()
                .map(e -> e.getUser().getId())
                .toList();
        assertThat(remainingUserIds).containsExactlyInAnyOrder(secondWaiterId, thirdWaiterId);

        List<Enrollment> activeEnrollments = enrollmentRepository.findAll().stream()
                .filter(e -> e.getStatus() != EnrollmentStatus.CANCELLED)
                .toList();
        assertThat(activeEnrollments).hasSize(1);
        assertThat(activeEnrollments.get(0).getUser().getId()).isEqualTo(firstWaiterId);
    }

    @Test
    @DisplayName("승격된 대기 row가 hard delete 된다 — soft delete였다면 row가 남아 카운트 1")
    void cancel_promotedWaiter_waitlistRowIsHardDeleted() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        long enrollmentId = persistPendingEnrollment(enrolledUserId, classId);
        persistWaitlistEntry(firstWaiterId, classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", enrolledUserId))
                .andExpect(status().isOk());

        Long remainingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_entries WHERE user_id = ? AND class_id = ?",
                Long.class,
                firstWaiterId, classId);
        assertThat(remainingRows).isZero();
    }

    @Test
    @DisplayName("FIFO 순서 — 두 명 대기 중 두 번째 등록자가 아닌 첫 등록자가 승격된다")
    void cancel_promotesFirstByCreatedAt() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        long enrollmentId = persistPendingEnrollment(enrolledUserId, classId);
        persistWaitlistEntry(firstWaiterId, classId);
        persistWaitlistEntry(secondWaiterId, classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", enrolledUserId))
                .andExpect(status().isOk());

        List<Enrollment> activeEnrollments = enrollmentRepository.findAll().stream()
                .filter(e -> e.getStatus() != EnrollmentStatus.CANCELLED)
                .toList();
        assertThat(activeEnrollments).hasSize(1);
        assertThat(activeEnrollments.get(0).getUser().getId()).isEqualTo(firstWaiterId);

        assertThat(waitlistRepository.count()).isEqualTo(1);
        assertThat(waitlistRepository.findAll().get(0).getUser().getId()).isEqualTo(secondWaiterId);
    }

    @Transactional
    long createClosedFullClass(long ownerId, int capacity) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                "Cancel Promote Class",
                "demo",
                new BigDecimal("10000.00"),
                capacity,
                FIXED_TODAY.plusDays(10),
                FIXED_TODAY.plusDays(40)
        );
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        for (int i = 0; i < capacity; i++) {
            cls.incrementCurrentCount();
        }
        courseClassRepository.saveAndFlush(cls);
        jdbcTemplate.update(
                "UPDATE classes SET status = 'CLOSED' WHERE id = ?",
                cls.getId());
        CourseClass refreshed = courseClassRepository.findById(cls.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ClassStatus.CLOSED);
        assertThat(refreshed.getCurrentCount()).isEqualTo(capacity);
        return cls.getId();
    }

    @Transactional
    long persistPendingEnrollment(long ownerUserId, long targetClassId) {
        User owner = userRepository.findById(ownerUserId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(targetClassId).orElseThrow();
        Enrollment enrollment = enrollmentRepository.saveAndFlush(new Enrollment(owner, cls));
        return enrollment.getId();
    }

    @Transactional
    void persistWaitlistEntry(long ownerUserId, long targetClassId) {
        User owner = userRepository.findById(ownerUserId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(targetClassId).orElseThrow();
        waitlistRepository.saveAndFlush(new WaitlistEntry(owner, cls));
    }

    private int rawCurrentCount(long classId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT current_count FROM classes WHERE id = ?",
                Integer.class,
                classId);
        assertThat(count).isNotNull();
        return count;
    }

    private String rawStatus(long classId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM classes WHERE id = ?",
                String.class,
                classId);
    }
}
