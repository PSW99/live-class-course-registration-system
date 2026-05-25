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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("POST /api/enrollments/{id}/confirm — 결제 확정 단일 스레드 통합 테스트")
class EnrollmentConfirmIntegrationTest extends PostgresRedisContainerSupport {

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
    private long classId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        User creator = userRepository.saveAndFlush(new User("Creator", "creator-confirm@example.com"));
        User learner = userRepository.saveAndFlush(new User("Learner", "learner-confirm@example.com"));
        creatorId = creator.getId();
        learnerId = learner.getId();
        classId = createOpenClass(creatorId, 10);
    }

    @AfterEach
    void cleanUp() {
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("PENDING 상태 본인 enrollment에 confirm을 호출하면 200 CONFIRMED와 confirmedAt(UTC)을 반환한다")
    void confirm_success_returnsConfirmedWithUtcTimestamp() throws Exception {
        long enrollmentId = persistPendingEnrollment(learnerId, classId);
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        mockMvc.perform(post("/api/enrollments/{id}/confirm", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value((int) enrollmentId))
                .andExpect(jsonPath("$.classId").value((int) classId))
                .andExpect(jsonPath("$.userId").value((int) learnerId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(persisted.getConfirmedAt())
                .isNotNull()
                .isBetween(before.minusSeconds(1), after.plusSeconds(1));
        assertThat(persisted.getConfirmedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("타인이 confirm을 호출하면 403 FORBIDDEN을 반환하고 상태가 PENDING으로 유지된다")
    void confirm_byNonOwner_returnsForbidden() throws Exception {
        long enrollmentId = persistPendingEnrollment(learnerId, classId);
        long otherUserId = userRepository
                .saveAndFlush(new User("Other", "other-confirm@example.com"))
                .getId();

        mockMvc.perform(post("/api/enrollments/{id}/confirm", enrollmentId)
                        .header("X-User-Id", otherUserId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(persisted.getConfirmedAt()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 enrollment id로 confirm을 호출하면 404 NOT_FOUND를 반환한다")
    void confirm_unknownEnrollmentId_returnsNotFound() throws Exception {
        long missingEnrollmentId = 9_999_999L;

        mockMvc.perform(post("/api/enrollments/{id}/confirm", missingEnrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("이미 CONFIRMED 상태인 enrollment에 confirm을 다시 호출하면 400 INVALID_STATUS_TRANSITION을 반환한다")
    void confirm_alreadyConfirmed_returnsInvalidStatusTransition() throws Exception {
        long enrollmentId = persistPendingEnrollment(learnerId, classId);

        mockMvc.perform(post("/api/enrollments/{id}/confirm", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/enrollments/{id}/confirm", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("enrollment")));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("CANCELLED 상태인 enrollment에 confirm을 호출하면 400 INVALID_STATUS_TRANSITION을 반환한다")
    void confirm_alreadyCancelled_returnsInvalidStatusTransition() throws Exception {
        long enrollmentId = persistPendingEnrollment(learnerId, classId);
        markCancelledDirectly(enrollmentId);

        mockMvc.perform(post("/api/enrollments/{id}/confirm", enrollmentId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("enrollment")));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("존재하지 않는 enrollment id와 임의 X-User-Id 조합은 403이 아니라 404를 우선 반환한다")
    void confirm_unknownIdWithArbitraryHeader_prefersNotFoundOverForbidden() throws Exception {
        long missingEnrollmentId = 9_999_999L;
        long arbitraryUserId = 7_777_777L;

        mockMvc.perform(post("/api/enrollments/{id}/confirm", missingEnrollmentId)
                        .header("X-User-Id", arbitraryUserId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
    void confirm_missingHeader_returnsMissingHeader() throws Exception {
        long enrollmentId = persistPendingEnrollment(learnerId, classId);

        mockMvc.perform(post("/api/enrollments/{id}/confirm", enrollmentId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        Enrollment persisted = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
    }

    @Transactional
    long persistPendingEnrollment(long ownerUserId, long targetClassId) {
        User owner = userRepository.findById(ownerUserId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(targetClassId).orElseThrow();
        Enrollment enrollment = enrollmentRepository.saveAndFlush(new Enrollment(owner, cls));
        return enrollment.getId();
    }

    private void markCancelledDirectly(long enrollmentId) {
        int updated = jdbcTemplate.update(
                "UPDATE enrollments SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant()),
                enrollmentId);
        assertThat(updated).isEqualTo(1);
    }

    @Transactional
    long createOpenClass(long ownerId, int capacity) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                "Confirm Demo Class",
                "demo",
                new BigDecimal("10000.00"),
                capacity,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
        );
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        courseClassRepository.saveAndFlush(cls);
        assertThat(cls.getStatus()).isEqualTo(ClassStatus.OPEN);
        return cls.getId();
    }
}
