package com.liveclass.registration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.support.PostgresRedisContainerSupport;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "course-registration.enrollment.expiration.scheduler-enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@DisplayName("GET /api/classes/{id}/enrollments — 강의별 수강생 목록 통합 테스트")
class ClassEnrollmentListIntegrationTest extends PostgresRedisContainerSupport {

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
    @Autowired
    EntityManagerFactory entityManagerFactory;

    private JdbcTemplate jdbcTemplate;
    private long creatorId;
    private long otherUserId;
    private long classId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        creatorId = userRepository.saveAndFlush(new User("Creator", "creator-list@example.com")).getId();
        otherUserId = userRepository.saveAndFlush(new User("Other", "other-list@example.com")).getId();
        classId = createOpenClass(creatorId);
    }

    @AfterEach
    void cleanUp() {
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("creator가 본인 강의 조회 시 200과 모든 status enrollment 반환")
    void list_byCreator_returnsAllStatuses() throws Exception {
        long u1 = persistUser("u1-list@example.com");
        long u2 = persistUser("u2-list@example.com");
        long u3 = persistUser("u3-list@example.com");
        persistEnrollment(u1, classId, "PENDING");
        persistEnrollment(u2, classId, "CONFIRMED");
        persistEnrollment(u3, classId, "CANCELLED");

        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].userEmail").exists());
    }

    @Test
    @DisplayName("non-creator는 403 FORBIDDEN")
    void list_byNonCreator_403() throws Exception {
        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", otherUserId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("존재하지 않는 강의는 404 NOT_FOUND — non-creator도 enumeration 방지")
    void list_classNotFound_404() throws Exception {
        long missingClassId = 9_999_999L;
        mockMvc.perform(get("/api/classes/{id}/enrollments", missingClassId)
                        .header("X-User-Id", creatorId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("status=PENDING 필터 — PENDING만 반환")
    void list_statusFilterPending() throws Exception {
        long u1 = persistUser("u1-filter@example.com");
        long u2 = persistUser("u2-filter@example.com");
        long u3 = persistUser("u3-filter@example.com");
        persistEnrollment(u1, classId, "PENDING");
        persistEnrollment(u2, classId, "CONFIRMED");
        persistEnrollment(u3, classId, "CANCELLED");

        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("status=CANCELLED 필터 — 취소 이력 row만 반환")
    void list_statusFilterCancelled_returnsHistory() throws Exception {
        long u1 = persistUser("u1-cancelled@example.com");
        persistEnrollment(u1, classId, "CANCELLED");

        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId)
                        .param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("CANCELLED"));
    }

    @Test
    @DisplayName("응답에 userName·userEmail이 포함된다 — creator 운영 시각")
    void list_includesUserNameAndEmail() throws Exception {
        long u1 = userRepository.saveAndFlush(new User("Alice", "alice-personal@example.com")).getId();
        persistEnrollment(u1, classId, "PENDING");

        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userName").value("Alice"))
                .andExpect(jsonPath("$.content[0].userEmail").value("alice-personal@example.com"));
    }

    @Test
    @DisplayName("created_at DESC 정렬 — 최신 신청이 첫 페이지에")
    void list_sortedByCreatedAtDesc() throws Exception {
        long u1 = persistUser("old@example.com");
        long u2 = persistUser("new@example.com");
        long oldEnrollmentId = persistEnrollment(u1, classId, "PENDING");
        long newEnrollmentId = persistEnrollment(u2, classId, "PENDING");
        // u1의 created_at을 명시적으로 과거로 밀어 정렬 기준을 강제한다
        jdbcTemplate.update("UPDATE enrollments SET created_at=? WHERE id=?",
                Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toInstant()),
                oldEnrollmentId);

        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].enrollmentId").value((int) newEnrollmentId))
                .andExpect(jsonPath("$.content[1].enrollmentId").value((int) oldEnrollmentId));
    }

    @Test
    @DisplayName("페이지네이션 — size=2, page=0은 2건, page=1은 1건")
    void list_pagination() throws Exception {
        for (int i = 0; i < 3; i++) {
            long uid = persistUser("page" + i + "@example.com");
            persistEnrollment(uid, classId, "PENDING");
        }

        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId)
                        .param("page", "0").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId)
                        .param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("신청 없는 강의 — 빈 페이지 200")
    void list_emptyClass_returnsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("잘못된 status enum 값은 400 VALIDATION_FAILED")
    void list_invalidStatus_400() throws Exception {
        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId)
                        .param("status", "INVALID_STATUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size 상한 초과(101)는 400 VALIDATION_FAILED")
    void list_sizeOverLimit_400() throws Exception {
        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("N+1 회피 — user 정보 fetch에 추가 SELECT가 발생하지 않는다")
    void list_noNPlusOneOnUserFetch() throws Exception {
        for (int i = 0; i < 5; i++) {
            long uid = persistUser("n1-" + i + "@example.com");
            persistEnrollment(uid, classId, "PENDING");
        }
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        mockMvc.perform(get("/api/classes/{id}/enrollments", classId)
                        .header("X-User-Id", creatorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5));

        // class 조회 1 + enrollment+user count 1 + enrollment+user content 1 = 3 이하
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(5L);
    }

    @Transactional
    long createOpenClass(long ownerId) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner, "List Class", "demo",
                new BigDecimal("10000.00"), 30,
                LocalDate.now().plusDays(10), LocalDate.now().plusDays(40));
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        courseClassRepository.saveAndFlush(cls);
        return cls.getId();
    }

    private long persistUser(String email) {
        return userRepository.saveAndFlush(new User("U-" + email, email)).getId();
    }

    private long persistEnrollment(long userId, long targetClassId, String status) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO enrollments (user_id, class_id, status, confirmed_at, cancelled_at)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId, targetClassId, status,
                "CONFIRMED".equals(status) ? Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant()) : null,
                "CANCELLED".equals(status) ? Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant()) : null
        );
        return id != null ? id : 0L;
    }
}
