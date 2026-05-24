package com.liveclass.registration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.service.EnrollmentLockFacade;
import com.liveclass.registration.support.PostgresRedisContainerSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("POST /api/enrollments — 수강 신청 단일 스레드 통합 테스트")
class EnrollmentIntegrationTest extends PostgresRedisContainerSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CourseClassRepository courseClassRepository;

    @Autowired
    EnrollmentRepository enrollmentRepository;

    @Autowired
    EnrollmentLockFacade enrollmentLockFacade;

    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    private long creatorId;
    private long learnerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        User creator = userRepository.saveAndFlush(new User("Creator", "creator-enroll@example.com"));
        User learner = userRepository.saveAndFlush(new User("Learner", "learner-enroll@example.com"));
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
    @DisplayName("정상 신청은 201 PENDING과 함께 current_count를 1 증가시킨다")
    void enroll_success_returnsCreatedAndIncrementsCurrentCount() throws Exception {
        long classId = createOpenClass(creatorId, 10);

        mockMvc.perform(post("/api/enrollments")
                        .header("X-User-Id", learnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", classId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enrollmentId").isNumber())
                .andExpect(jsonPath("$.classId").value((int) classId))
                .andExpect(jsonPath("$.userId").value((int) learnerId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        assertThat(cls.getCurrentCount()).isEqualTo(1);
        assertThat(cls.getStatus()).isEqualTo(ClassStatus.OPEN);
        assertThat(enrollmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("DRAFT 강의에 신청하면 400 CLASS_NOT_OPEN을 반환한다")
    void enroll_draftClass_returnsClassNotOpen() throws Exception {
        long classId = createDraftClass(creatorId);

        mockMvc.perform(post("/api/enrollments")
                        .header("X-User-Id", learnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", classId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLASS_NOT_OPEN"));

        assertThat(enrollmentRepository.count()).isZero();
    }

    @Test
    @DisplayName("CLOSED 강의에 신청하면 400 CLASS_NOT_OPEN을 반환한다")
    void enroll_closedClass_returnsClassNotOpen() throws Exception {
        long classId = createClosedClass(creatorId);

        mockMvc.perform(post("/api/enrollments")
                        .header("X-User-Id", learnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", classId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLASS_NOT_OPEN"));

        assertThat(enrollmentRepository.count()).isZero();
    }

    @Test
    @DisplayName("본인이 등록한 강의에 본인이 신청하면 400 SELF_ENROLLMENT_FORBIDDEN을 반환한다")
    void enroll_selfEnrollment_returnsForbidden() throws Exception {
        long classId = createOpenClass(creatorId, 10);

        mockMvc.perform(post("/api/enrollments")
                        .header("X-User-Id", creatorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", classId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_ENROLLMENT_FORBIDDEN"));

        assertThat(enrollmentRepository.count()).isZero();
    }

    @Test
    @DisplayName("동일 사용자가 같은 강의에 두 번째 활성 신청을 보내면 409 DUPLICATE_ENROLLMENT를 반환한다")
    void enroll_duplicateActiveEnrollment_returnsConflict() throws Exception {
        long classId = createOpenClass(creatorId, 10);

        mockMvc.perform(post("/api/enrollments")
                        .header("X-User-Id", learnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", classId))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/enrollments")
                        .header("X-User-Id", learnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", classId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ENROLLMENT"));

        assertThat(enrollmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("정원이 가득 찬 강의에 추가 신청을 보내면 409 CAPACITY_EXCEEDED를 반환한다")
    void enroll_capacityExceeded_returnsConflict() throws Exception {
        long classId = createOpenClass(creatorId, 1);
        fillCapacityDirectly(classId);

        long anotherLearnerId = userRepository
                .saveAndFlush(new User("Other", "other-enroll@example.com"))
                .getId();

        mockMvc.perform(post("/api/enrollments")
                        .header("X-User-Id", anotherLearnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", classId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));
    }

    @Test
    @DisplayName("존재하지 않는 classId로 신청하면 404 NOT_FOUND를 반환한다")
    void enroll_unknownClassId_returnsNotFound() throws Exception {
        long missingClassId = 9_999_999L;

        mockMvc.perform(post("/api/enrollments")
                        .header("X-User-Id", learnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", missingClassId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
    void enroll_missingHeader_returns400() throws Exception {
        long classId = createOpenClass(creatorId, 10);

        mockMvc.perform(post("/api/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", classId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("classId가 음수이면 400 VALIDATION_FAILED를 반환한다")
    void enroll_negativeClassId_returnsValidationFailed() throws Exception {
        mockMvc.perform(post("/api/enrollments")
                        .header("X-User-Id", learnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("classId", -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("정원 1인 강의에 한 명이 신청하면 current_count=1, status=CLOSED로 자동 마감된다")
    void br06_lastSeatAutoClosesClass() {
        long classId = createOpenClass(creatorId, 1);

        Enrollment enrollment = enrollmentLockFacade.enroll(learnerId, classId);

        assertThat(enrollment.getId()).isNotNull();
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        assertThat(cls.getCurrentCount()).isEqualTo(1);
        assertThat(cls.getStatus()).isEqualTo(ClassStatus.CLOSED);
    }

    @Test
    @DisplayName("정원 3인 강의에 2명이 신청하면 OPEN 상태가 유지되고 current_count=2가 된다")
    void br06_belowCapacityKeepsOpen() {
        long classId = createOpenClass(creatorId, 3);
        long secondLearnerId = userRepository
                .saveAndFlush(new User("Second", "second-enroll@example.com"))
                .getId();

        enrollmentLockFacade.enroll(learnerId, classId);
        enrollmentLockFacade.enroll(secondLearnerId, classId);

        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        assertThat(cls.getCurrentCount()).isEqualTo(2);
        assertThat(cls.getStatus()).isEqualTo(ClassStatus.OPEN);
    }

    @Test
    @DisplayName("정원 3인 강의에 3번째 신청이 들어오면 CLOSED로 전이한다")
    void br06_capacityReachedTransitionsToClosed() {
        long classId = createOpenClass(creatorId, 3);
        long secondLearnerId = userRepository
                .saveAndFlush(new User("Second", "second2-enroll@example.com"))
                .getId();
        long thirdLearnerId = userRepository
                .saveAndFlush(new User("Third", "third-enroll@example.com"))
                .getId();

        enrollmentLockFacade.enroll(learnerId, classId);
        enrollmentLockFacade.enroll(secondLearnerId, classId);
        enrollmentLockFacade.enroll(thirdLearnerId, classId);

        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        assertThat(cls.getCurrentCount()).isEqualTo(3);
        assertThat(cls.getStatus()).isEqualTo(ClassStatus.CLOSED);
    }

    private long createDraftClass(long ownerId) {
        return persistClass(ownerId, 10, ClassStatus.DRAFT, 0);
    }

    private long createOpenClass(long ownerId, int capacity) {
        return persistClass(ownerId, capacity, ClassStatus.OPEN, 0);
    }

    private long createClosedClass(long ownerId) {
        return persistClass(ownerId, 5, ClassStatus.CLOSED, 0);
    }

    @Transactional
    long persistClass(long ownerId, int capacity, ClassStatus targetStatus, int initialCount) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                "Concurrency Demo",
                "demo",
                new BigDecimal("10000.00"),
                capacity,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
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

    private void fillCapacityDirectly(long classId) {
        CourseClass cls = courseClassRepository.findById(classId).orElseThrow();
        int capacity = cls.getCapacity();
        jdbcTemplate.update(
                "UPDATE classes SET current_count = ? WHERE id = ?",
                capacity, classId);
    }
}
