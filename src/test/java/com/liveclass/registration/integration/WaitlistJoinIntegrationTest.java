package com.liveclass.registration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.repository.WaitlistRepository;
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
@DisplayName("POST /api/classes/{id}/waitlist — 대기열 등록 단일 스레드 통합 테스트")
class WaitlistJoinIntegrationTest extends PostgresRedisContainerSupport {

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
    private long learnerId;
    private long secondLearnerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        User creator = userRepository.saveAndFlush(new User("Creator", "creator-wl-join@example.com"));
        User learner = userRepository.saveAndFlush(new User("Learner", "learner-wl-join@example.com"));
        User second = userRepository.saveAndFlush(new User("Second", "second-wl-join@example.com"));
        creatorId = creator.getId();
        learnerId = learner.getId();
        secondLearnerId = second.getId();
    }

    @AfterEach
    void cleanUp() {
        waitlistRepository.deleteAllInBatch();
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("정원 가득 찬 CLOSED 강의에 대기 등록하면 201과 position=1을 반환하고 row가 생성된다")
    void join_closedFullClass_returnsCreatedWithPositionOne() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.waitlistId").isNumber())
                .andExpect(jsonPath("$.classId").value((int) classId))
                .andExpect(jsonPath("$.userId").value((int) learnerId))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        assertThat(waitlistRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("두 사용자가 순서대로 대기 등록하면 FIFO 순번 1, 2를 반환한다")
    void join_twoUsersSequentially_returnsFifoPositions() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1));

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", secondLearnerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(2));

        assertThat(waitlistRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("정원에 자리가 남은 OPEN 강의에 대기 등록을 시도하면 400 CAPACITY_AVAILABLE을 반환한다")
    void join_openClassWithAvailableSeats_returnsCapacityAvailable() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 10, 0);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPACITY_AVAILABLE"));

        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("DRAFT 강의에 대기 등록을 시도하면 400 CLASS_NOT_OPEN을 반환한다")
    void join_draftClass_returnsClassNotOpen() throws Exception {
        long classId = createDraftClass(creatorId, 1);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLASS_NOT_OPEN"));

        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("본인이 등록한 강의에 본인이 대기 등록을 시도하면 400 SELF_WAITLIST_FORBIDDEN을 반환한다")
    void join_selfWaitlist_returnsSelfWaitlistForbidden() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", creatorId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_WAITLIST_FORBIDDEN"));

        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("동일 강의에 활성 PENDING enrollment를 가진 사용자가 대기 등록을 시도하면 409 ACTIVE_ENROLLMENT_EXISTS를 반환한다")
    void join_userWithPendingEnrollment_returnsActiveEnrollmentExists() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 1, 0);
        persistPendingEnrollment(learnerId, classId);
        fillCapacityAndCloseDirectly(classId);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_ENROLLMENT_EXISTS"));

        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("동일 강의에 활성 CONFIRMED enrollment를 가진 사용자가 대기 등록을 시도하면 409 ACTIVE_ENROLLMENT_EXISTS를 반환한다")
    void join_userWithConfirmedEnrollment_returnsActiveEnrollmentExists() throws Exception {
        long classId = createOpenClassWithCount(creatorId, 1, 0);
        long enrollmentId = persistPendingEnrollment(learnerId, classId);
        markEnrollmentConfirmed(enrollmentId);
        fillCapacityAndCloseDirectly(classId);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_ENROLLMENT_EXISTS"));

        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("동일 사용자가 이미 대기 등록된 상태에서 재등록을 시도하면 409 DUPLICATE_WAITLIST를 반환한다")
    void join_duplicateWaitlist_returnsConflict() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_WAITLIST"));

        assertThat(waitlistRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하지 않는 강의 id로 대기 등록을 시도하면 404 NOT_FOUND를 반환한다")
    void join_unknownClassId_returnsNotFound() throws Exception {
        long missingClassId = 9_999_999L;

        mockMvc.perform(post("/api/classes/{classId}/waitlist", missingClassId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("존재하지 않는 강의 id와 본인 X-User-Id 조합은 다른 검증보다 404 NOT_FOUND를 우선 반환한다")
    void join_unknownClassWithCreatorHeader_prefersNotFound() throws Exception {
        long missingClassId = 9_999_999L;

        mockMvc.perform(post("/api/classes/{classId}/waitlist", missingClassId)
                        .header("X-User-Id", creatorId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
    void join_missingHeader_returnsMissingHeader() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("대기 row를 삭제한 뒤 같은 사용자가 다시 대기 등록하면 201을 반환한다")
    void join_afterLeaveSameUser_succeeds() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isCreated());

        waitlistRepository.deleteAllInBatch();

        mockMvc.perform(post("/api/classes/{classId}/waitlist", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1));

        assertThat(waitlistRepository.count()).isEqualTo(1);
    }

    @Transactional
    long createDraftClass(long ownerId, int capacity) {
        return persistClass(ownerId, capacity, ClassStatus.DRAFT, 0);
    }

    @Transactional
    long createOpenClassWithCount(long ownerId, int capacity, int initialCount) {
        return persistClass(ownerId, capacity, ClassStatus.OPEN, initialCount);
    }

    @Transactional
    long createClosedFullClass(long ownerId, int capacity) {
        long classId = persistClass(ownerId, capacity, ClassStatus.OPEN, 0);
        fillCapacityAndCloseDirectly(classId);
        return classId;
    }

    @Transactional
    long persistClass(long ownerId, int capacity, ClassStatus targetStatus, int initialCount) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                "Waitlist Demo Class",
                "demo",
                new BigDecimal("10000.00"),
                capacity,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)
        );
        cls = courseClassRepository.saveAndFlush(cls);
        if (targetStatus == ClassStatus.OPEN || targetStatus == ClassStatus.CLOSED) {
            cls.open();
        }
        if (targetStatus == ClassStatus.CLOSED) {
            cls.close();
        }
        for (int i = 0; i < initialCount; i++) {
            cls.incrementCurrentCount();
        }
        courseClassRepository.saveAndFlush(cls);
        return cls.getId();
    }

    private void fillCapacityAndCloseDirectly(long classId) {
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        int capacity = cls.getCapacity();
        jdbcTemplate.update(
                "UPDATE classes SET current_count = ?, status = 'CLOSED' WHERE id = ?",
                capacity, classId);
    }

    @Transactional
    long persistPendingEnrollment(long ownerUserId, long targetClassId) {
        User owner = userRepository.findById(ownerUserId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(targetClassId).orElseThrow();
        Enrollment enrollment = enrollmentRepository.saveAndFlush(new Enrollment(owner, cls));
        return enrollment.getId();
    }

    private void markEnrollmentConfirmed(long enrollmentId) {
        OffsetDateTime confirmedAt = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = jdbcTemplate.update(
                "UPDATE enrollments SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?",
                Timestamp.from(confirmedAt.toInstant()),
                enrollmentId);
        assertThat(updated).isEqualTo(1);
    }
}
