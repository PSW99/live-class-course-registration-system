package com.liveclass.registration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.support.PostgresRedisContainerSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
@Import(EnrollmentCancelIntegrationTest.FixedClockConfig.class)
@DisplayName("POST /api/enrollments/{id}/cancel — 수강 취소 단일 스레드 통합 테스트")
class EnrollmentCancelIntegrationTest extends PostgresRedisContainerSupport {

    private static final OffsetDateTime FIXED_NOW =
            OffsetDateTime.parse("2026-05-25T10:00:00Z");
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
    DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    private long creatorId;
    private long learnerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        User creator = userRepository.saveAndFlush(new User("Creator", "creator-cancel@example.com"));
        User learner = userRepository.saveAndFlush(new User("Learner", "learner-cancel@example.com"));
        creatorId = creator.getId();
        learnerId = learner.getId();
    }

    @AfterEach
    void cleanUp() {
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("PENDING 상태 enrollment를 본인이 취소하면 200 CANCELLED와 cancelledAt(UTC)을 반환하고 current_count가 1 감소한다")
    void cancel_pendingByOwner_returnsCancelledAndDecrementsCount() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY.plusDays(5), 1);
        long enrollmentId = persistPendingEnrollment(learnerId, classId);
        int countBefore = rawCurrentCount(classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value((int) enrollmentId))
                .andExpect(jsonPath("$.classId").value((int) classId))
                .andExpect(jsonPath("$.userId").value((int) learnerId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.confirmedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(persisted.getCancelledAt())
                .isNotNull()
                .isEqualTo(FIXED_NOW);
        assertThat(persisted.getCancelledAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(persisted.getConfirmedAt()).isNull();
        assertThat(rawCurrentCount(classId)).isEqualTo(countBefore - 1);
    }

    @Test
    @DisplayName("CONFIRMED 상태 enrollment를 7일 이내·시작 전에 취소하면 200 CANCELLED를 반환하고 confirmedAt이 보존되며 current_count가 1 감소한다")
    void cancel_confirmedWithinWindow_returnsCancelledAndPreservesConfirmedAt() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY.plusDays(3), 1);
        OffsetDateTime confirmedAt = FIXED_NOW.minusDays(2);
        long enrollmentId = persistConfirmedEnrollment(learnerId, classId, confirmedAt);
        int countBefore = rawCurrentCount(classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(persisted.getConfirmedAt()).isNotNull();
        assertThat(persisted.getCancelledAt()).isEqualTo(FIXED_NOW);
        assertThat(rawCurrentCount(classId)).isEqualTo(countBefore - 1);
    }

    @Test
    @DisplayName("CONFIRMED 상태에서 confirmedAt이 정확히 7일 전이면 400 CANCEL_PERIOD_EXPIRED를 반환하고 current_count는 보존된다")
    void cancel_confirmedAtExactlySevenDaysAgo_returnsCancelPeriodExpired() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY.plusDays(3), 1);
        OffsetDateTime confirmedAt = FIXED_NOW.minusDays(7);
        long enrollmentId = persistConfirmedEnrollment(learnerId, classId, confirmedAt);
        int countBefore = rawCurrentCount(classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANCEL_PERIOD_EXPIRED"));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(persisted.getCancelledAt()).isNull();
        assertThat(rawCurrentCount(classId)).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("CONFIRMED 상태에서 confirmedAt이 7일 + 1초 전이면 400 CANCEL_PERIOD_EXPIRED를 반환한다")
    void cancel_confirmedAtSevenDaysAndOneSecondAgo_returnsCancelPeriodExpired() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY.plusDays(3), 1);
        OffsetDateTime confirmedAt = FIXED_NOW.minusDays(7).minusSeconds(1);
        long enrollmentId = persistConfirmedEnrollment(learnerId, classId, confirmedAt);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANCEL_PERIOD_EXPIRED"));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("CONFIRMED 상태에서 confirmedAt이 7일 - 1초 전이면 200 CANCELLED를 반환한다")
    void cancel_confirmedAtSevenDaysMinusOneSecondAgo_succeeds() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY.plusDays(3), 1);
        OffsetDateTime confirmedAt = FIXED_NOW.minusDays(7).plusSeconds(1);
        long enrollmentId = persistConfirmedEnrollment(learnerId, classId, confirmedAt);
        int countBefore = rawCurrentCount(classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(rawCurrentCount(classId)).isEqualTo(countBefore - 1);
    }

    @Test
    @DisplayName("CONFIRMED 상태에서 강의 시작일이 오늘이면 400 CLASS_ALREADY_STARTED를 반환한다")
    void cancel_classStartsToday_returnsClassAlreadyStarted() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY, 1);
        OffsetDateTime confirmedAt = FIXED_NOW.minusDays(1);
        long enrollmentId = persistConfirmedEnrollment(learnerId, classId, confirmedAt);
        int countBefore = rawCurrentCount(classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLASS_ALREADY_STARTED"));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(rawCurrentCount(classId)).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("CONFIRMED 상태에서 강의 시작일이 내일이면 200 CANCELLED를 반환한다")
    void cancel_classStartsTomorrow_succeeds() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY.plusDays(1), 1);
        OffsetDateTime confirmedAt = FIXED_NOW.minusDays(1);
        long enrollmentId = persistConfirmedEnrollment(learnerId, classId, confirmedAt);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("7일이 초과된 동시에 강의 시작일이 도래한 경우 CANCEL_PERIOD_EXPIRED가 우선 반환된다")
    void cancel_bothViolations_prefersCancelPeriodExpired() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY, 1);
        OffsetDateTime confirmedAt = FIXED_NOW.minusDays(8);
        long enrollmentId = persistConfirmedEnrollment(learnerId, classId, confirmedAt);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANCEL_PERIOD_EXPIRED"));
    }

    @Test
    @DisplayName("이미 CANCELLED 상태인 enrollment를 재취소하면 400 INVALID_STATUS_TRANSITION을 반환한다")
    void cancel_alreadyCancelled_returnsInvalidStatusTransition() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY.plusDays(5), 1);
        long enrollmentId = persistPendingEnrollment(learnerId, classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isOk());

        int countAfterFirstCancel = rawCurrentCount(classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        assertThat(rawCurrentCount(classId)).isEqualTo(countAfterFirstCancel);
    }

    @Test
    @DisplayName("타인이 enrollment 취소를 시도하면 403 FORBIDDEN을 반환하고 상태가 보존된다")
    void cancel_byNonOwner_returnsForbidden() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY.plusDays(5), 1);
        long enrollmentId = persistPendingEnrollment(learnerId, classId);
        long otherUserId = userRepository
                .saveAndFlush(new User("Other", "other-cancel@example.com"))
                .getId();
        int countBefore = rawCurrentCount(classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId)
                        .header("X-User-Id", otherUserId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(persisted.getCancelledAt()).isNull();
        assertThat(rawCurrentCount(classId)).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("존재하지 않는 enrollment id로 취소를 호출하면 404 NOT_FOUND를 반환한다")
    void cancel_unknownEnrollmentId_returnsNotFound() throws Exception {
        long missingEnrollmentId = 9_999_999L;

        mockMvc.perform(post("/api/enrollments/{id}/cancel", missingEnrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("존재하지 않는 enrollment id와 임의 X-User-Id 조합은 403이 아니라 404를 우선 반환한다")
    void cancel_unknownIdWithArbitraryHeader_prefersNotFoundOverForbidden() throws Exception {
        long missingEnrollmentId = 9_999_999L;
        long arbitraryUserId = 7_777_777L;

        mockMvc.perform(post("/api/enrollments/{id}/cancel", missingEnrollmentId)
                        .header("X-User-Id", arbitraryUserId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 누락되면 400 MISSING_HEADER를 반환하고 상태는 PENDING으로 유지된다")
    void cancel_missingHeader_returnsMissingHeader() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, FIXED_TODAY.plusDays(5), 1);
        long enrollmentId = persistPendingEnrollment(learnerId, classId);
        int countBefore = rawCurrentCount(classId);

        mockMvc.perform(post("/api/enrollments/{id}/cancel", enrollmentId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(rawCurrentCount(classId)).isEqualTo(countBefore);
    }

    @Transactional
    long persistPendingEnrollment(long ownerUserId, long targetClassId) {
        User owner = userRepository.findById(ownerUserId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(targetClassId).orElseThrow();
        Enrollment enrollment = enrollmentRepository.saveAndFlush(new Enrollment(owner, cls));
        return enrollment.getId();
    }

    @Transactional
    long persistConfirmedEnrollment(long ownerUserId, long targetClassId, OffsetDateTime confirmedAt) {
        long enrollmentId = persistPendingEnrollment(ownerUserId, targetClassId);
        Instant instant = confirmedAt.toInstant();
        int updated = jdbcTemplate.update(
                "UPDATE enrollments SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?",
                Timestamp.from(instant),
                enrollmentId);
        assertThat(updated).isEqualTo(1);
        return enrollmentId;
    }

    @Transactional
    long createOpenClassWithCount(long ownerId, int capacity, LocalDate startDate, int initialCount) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                "Cancel Demo Class",
                "demo",
                new BigDecimal("10000.00"),
                capacity,
                startDate,
                startDate.plusDays(29)
        );
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        for (int i = 0; i < initialCount; i++) {
            cls.incrementCurrentCount();
        }
        cls = courseClassRepository.saveAndFlush(cls);
        assertThat(cls.getStatus()).isEqualTo(ClassStatus.OPEN);
        assertThat(cls.getCurrentCount()).isEqualTo(initialCount);
        return cls.getId();
    }

    private int rawCurrentCount(long classId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT current_count FROM classes WHERE id = ?",
                Integer.class,
                classId);
        assertThat(count).isNotNull();
        return count;
    }
}
