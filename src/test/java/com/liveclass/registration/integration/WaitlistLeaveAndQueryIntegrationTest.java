package com.liveclass.registration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.domain.WaitlistEntry;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.repository.WaitlistRepository;
import com.liveclass.registration.support.PostgresRedisContainerSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
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
@DisplayName("/api/classes/{id}/waitlist/me — 대기열 이탈/조회 통합 테스트")
class WaitlistLeaveAndQueryIntegrationTest extends PostgresRedisContainerSupport {

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
    private long thirdLearnerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        User creator = userRepository.saveAndFlush(new User("Creator", "creator-wl-leave@example.com"));
        User learner = userRepository.saveAndFlush(new User("Learner", "learner-wl-leave@example.com"));
        User second = userRepository.saveAndFlush(new User("Second", "second-wl-leave@example.com"));
        User third = userRepository.saveAndFlush(new User("Third", "third-wl-leave@example.com"));
        creatorId = creator.getId();
        learnerId = learner.getId();
        secondLearnerId = second.getId();
        thirdLearnerId = third.getId();
    }

    @AfterEach
    void cleanUp() {
        waitlistRepository.deleteAllInBatch();
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("본인 대기 row가 있는 사용자가 이탈을 요청하면 204를 반환하고 row가 삭제된다")
    void leave_existingEntry_returnsNoContent() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        persistWaitlistEntry(learnerId, classId);

        mockMvc.perform(delete("/api/classes/{classId}/waitlist/me", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isNoContent());

        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("본인 대기 row가 없는 강의에 이탈을 요청하면 404 NOT_FOUND를 반환한다")
    void leave_withoutOwnEntry_returnsNotFound() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        persistWaitlistEntry(secondLearnerId, classId);

        mockMvc.perform(delete("/api/classes/{classId}/waitlist/me", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(waitlistRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하지 않는 강의 id로 이탈을 요청하면 404 NOT_FOUND를 반환한다")
    void leave_unknownClassId_returnsNotFound() throws Exception {
        long missingClassId = 9_999_999L;

        mockMvc.perform(delete("/api/classes/{classId}/waitlist/me", missingClassId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("이탈 시 X-User-Id 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
    void leave_missingHeader_returnsMissingHeader() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);

        mockMvc.perform(delete("/api/classes/{classId}/waitlist/me", classId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("본인 대기 항목 조회 시 첫 등록자는 position=1을 반환한다")
    void findMine_firstRegistrant_returnsPositionOne() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        persistWaitlistEntry(learnerId, classId);

        mockMvc.perform(get("/api/classes/{classId}/waitlist/me", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classId").value((int) classId))
                .andExpect(jsonPath("$.userId").value((int) learnerId))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("앞선 대기자 2명이 있는 사용자는 position=3을 반환한다")
    void findMine_withTwoAhead_returnsPositionThree() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        persistWaitlistEntry(learnerId, classId);
        persistWaitlistEntry(secondLearnerId, classId);
        persistWaitlistEntry(thirdLearnerId, classId);

        mockMvc.perform(get("/api/classes/{classId}/waitlist/me", classId)
                        .header("X-User-Id", thirdLearnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value((int) thirdLearnerId))
                .andExpect(jsonPath("$.position").value(3));

        mockMvc.perform(get("/api/classes/{classId}/waitlist/me", classId)
                        .header("X-User-Id", secondLearnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value((int) secondLearnerId))
                .andExpect(jsonPath("$.position").value(2));
    }

    @Test
    @DisplayName("본인 대기 row가 없는 강의에 조회를 요청하면 404 NOT_FOUND를 반환한다")
    void findMine_noEntry_returnsNotFound() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        persistWaitlistEntry(secondLearnerId, classId);

        mockMvc.perform(get("/api/classes/{classId}/waitlist/me", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("존재하지 않는 강의 id로 조회를 요청하면 404 NOT_FOUND를 반환한다")
    void findMine_unknownClassId_returnsNotFound() throws Exception {
        long missingClassId = 9_999_999L;

        mockMvc.perform(get("/api/classes/{classId}/waitlist/me", missingClassId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 사용자의 대기는 본인 조회 응답에 보이지 않는다 — 본인 row가 없으면 다른 사용자가 있어도 404를 반환한다")
    void findMine_doesNotExposeOthersEntries() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);
        persistWaitlistEntry(secondLearnerId, classId);
        persistWaitlistEntry(thirdLearnerId, classId);

        mockMvc.perform(get("/api/classes/{classId}/waitlist/me", classId)
                        .header("X-User-Id", learnerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("조회 시 X-User-Id 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
    void findMine_missingHeader_returnsMissingHeader() throws Exception {
        long classId = createClosedFullClass(creatorId, 1);

        mockMvc.perform(get("/api/classes/{classId}/waitlist/me", classId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Transactional
    long createClosedFullClass(long ownerId, int capacity) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                "Waitlist Leave Class",
                "demo",
                new BigDecimal("10000.00"),
                capacity,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)
        );
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        courseClassRepository.saveAndFlush(cls);
        jdbcTemplate.update(
                "UPDATE classes SET current_count = ?, status = 'CLOSED' WHERE id = ?",
                capacity, cls.getId());
        CourseClass refreshed = courseClassRepository.findById(cls.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ClassStatus.CLOSED);
        return cls.getId();
    }

    @Transactional
    long persistWaitlistEntry(long ownerUserId, long targetClassId) {
        User owner = userRepository.findById(ownerUserId).orElseThrow();
        CourseClass cls = courseClassRepository.findById(targetClassId).orElseThrow();
        WaitlistEntry entry = waitlistRepository.saveAndFlush(new WaitlistEntry(owner, cls));
        return entry.getId();
    }
}
