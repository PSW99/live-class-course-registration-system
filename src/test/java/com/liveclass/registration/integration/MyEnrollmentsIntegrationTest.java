package com.liveclass.registration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.support.MockRedissonClientConfig;
import com.liveclass.registration.support.PostgresContainerSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.autoconfigure.exclude="
                + "org.redisson.spring.starter.RedissonAutoConfigurationV2,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
)
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MockRedissonClientConfig.class)
@DisplayName("GET /api/enrollments/me — 내 신청 목록 통합 테스트")
class MyEnrollmentsIntegrationTest extends PostgresContainerSupport {

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
    DataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private long creatorId;
    private long meId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        User creator = userRepository.saveAndFlush(new User("Creator", "creator-myenroll@example.com"));
        User me = userRepository.saveAndFlush(new User("Me", "me-myenroll@example.com"));
        User other = userRepository.saveAndFlush(new User("Other", "other-myenroll@example.com"));
        creatorId = creator.getId();
        meId = me.getId();
        otherUserId = other.getId();
    }

    @AfterEach
    void cleanUp() {
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("본인 enrollment 3건이 있으면 200과 함께 created_at DESC 순서로 모두 반환한다")
    void listMine_threeOwnEnrollments_returnsAllSortedDesc() throws Exception {
        long classA = persistOpenClass("A");
        long classB = persistOpenClass("B");
        long classC = persistOpenClass("C");
        long oldest = insertEnrollment(meId, classA, "PENDING",
                OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        long middle = insertEnrollment(meId, classB, "CONFIRMED",
                OffsetDateTime.of(2026, 5, 10, 10, 0, 0, 0, ZoneOffset.UTC));
        long newest = insertEnrollment(meId, classC, "PENDING",
                OffsetDateTime.of(2026, 5, 20, 10, 0, 0, 0, ZoneOffset.UTC));

        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[*].enrollmentId",
                        contains((int) newest, (int) middle, (int) oldest)))
                .andExpect(jsonPath("$.content[*].userId",
                        everyItem(org.hamcrest.Matchers.is((int) meId))));
    }

    @Test
    @DisplayName("본인 enrollment가 없으면 200과 빈 content, totalElements=0을 반환한다")
    void listMine_noEnrollments_returnsEmptyContent() throws Exception {
        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("타인 enrollment가 섞여 있어도 본인 enrollment만 반환한다")
    void listMine_othersExcluded_returnsOnlyOwnEnrollments() throws Exception {
        long classA = persistOpenClass("A");
        long classB = persistOpenClass("B");
        long classC = persistOpenClass("C");
        long mine1 = insertEnrollment(meId, classA, "PENDING",
                OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        long mine2 = insertEnrollment(meId, classB, "CONFIRMED",
                OffsetDateTime.of(2026, 5, 2, 10, 0, 0, 0, ZoneOffset.UTC));
        long othersA = insertEnrollment(otherUserId, classA, "PENDING",
                OffsetDateTime.of(2026, 5, 3, 10, 0, 0, 0, ZoneOffset.UTC));
        long othersB = insertEnrollment(otherUserId, classC, "CONFIRMED",
                OffsetDateTime.of(2026, 5, 4, 10, 0, 0, 0, ZoneOffset.UTC));

        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].enrollmentId",
                        org.hamcrest.Matchers.containsInAnyOrder((int) mine1, (int) mine2)))
                .andExpect(jsonPath("$.content[*].enrollmentId",
                        not(org.hamcrest.Matchers.hasItem((int) othersA))))
                .andExpect(jsonPath("$.content[*].enrollmentId",
                        not(org.hamcrest.Matchers.hasItem((int) othersB))))
                .andExpect(jsonPath("$.content[*].userId",
                        everyItem(org.hamcrest.Matchers.is((int) meId))));
    }

    @Test
    @DisplayName("size=1, page=0이면 가장 최근 enrollment만 반환한다")
    void listMine_sizeOneFirstPage_returnsNewest() throws Exception {
        long classA = persistOpenClass("A");
        long classB = persistOpenClass("B");
        long classC = persistOpenClass("C");
        insertEnrollment(meId, classA, "PENDING",
                OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        insertEnrollment(meId, classB, "PENDING",
                OffsetDateTime.of(2026, 5, 2, 10, 0, 0, 0, ZoneOffset.UTC));
        long newest = insertEnrollment(meId, classC, "PENDING",
                OffsetDateTime.of(2026, 5, 3, 10, 0, 0, 0, ZoneOffset.UTC));

        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId)
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].enrollmentId").value((int) newest));
    }

    @Test
    @DisplayName("마지막 페이지(size=1, page=2)는 가장 오래된 enrollment만 반환한다")
    void listMine_lastPage_returnsOldest() throws Exception {
        long classA = persistOpenClass("A");
        long classB = persistOpenClass("B");
        long classC = persistOpenClass("C");
        long oldest = insertEnrollment(meId, classA, "PENDING",
                OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        insertEnrollment(meId, classB, "PENDING",
                OffsetDateTime.of(2026, 5, 2, 10, 0, 0, 0, ZoneOffset.UTC));
        insertEnrollment(meId, classC, "PENDING",
                OffsetDateTime.of(2026, 5, 3, 10, 0, 0, 0, ZoneOffset.UTC));

        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId)
                        .param("page", "2")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.content[0].enrollmentId").value((int) oldest));
    }

    @Test
    @DisplayName("size=100(경계)은 200과 정상 응답을 반환한다")
    void listMine_sizeAtUpperBound_returnsOk() throws Exception {
        long classA = persistOpenClass("A");
        insertEnrollment(meId, classA, "PENDING",
                OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC));

        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("size=101은 400 VALIDATION_FAILED를 반환한다")
    void listMine_sizeOverUpperBound_returnsValidationFailed() throws Exception {
        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("size")))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("100")));
    }

    @Test
    @DisplayName("size=0은 400 VALIDATION_FAILED를 반환한다")
    void listMine_sizeZero_returnsValidationFailed() throws Exception {
        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId)
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("size")));
    }

    @Test
    @DisplayName("page=-1은 400 VALIDATION_FAILED를 반환한다")
    void listMine_negativePage_returnsValidationFailed() throws Exception {
        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("page")));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
    void listMine_missingHeader_returnsMissingHeader() throws Exception {
        mockMvc.perform(get("/api/enrollments/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("X-User-Id")));
    }

    @Test
    @DisplayName("X-User-Id가 비숫자이면 400 INVALID_HEADER를 반환한다")
    void listMine_nonNumericHeader_returnsInvalidHeader() throws Exception {
        mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HEADER"));
    }

    @Test
    @DisplayName("응답 JSON은 정확히 content·page·size·totalElements·totalPages 5개 키만 노출한다")
    void listMine_responseSchema_hasExactlyFiveKeys() throws Exception {
        long classA = persistOpenClass("A");
        insertEnrollment(meId, classA, "PENDING",
                OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC));

        MvcResult result = mockMvc.perform(get("/api/enrollments/me")
                        .header("X-User-Id", meId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        List<String> actualKeys = collectFieldNames(root);
        assertThat(actualKeys)
                .containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages");
        assertThat(root.has("pageable")).isFalse();
        assertThat(root.has("sort")).isFalse();
        assertThat(root.has("numberOfElements")).isFalse();
        assertThat(root.has("first")).isFalse();
        assertThat(root.has("last")).isFalse();
        assertThat(root.has("empty")).isFalse();
        assertThat(root.has("number")).isFalse();
    }

    private List<String> collectFieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    @Transactional
    long persistOpenClass(String title) {
        User owner = userRepository.findById(creatorId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                "Class " + title,
                "desc",
                new BigDecimal("10000.00"),
                10,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
        );
        cls = courseClassRepository.saveAndFlush(cls);
        cls.open();
        courseClassRepository.saveAndFlush(cls);
        return cls.getId();
    }

    private long insertEnrollment(long userId, long classId, String enrollmentStatus, OffsetDateTime createdAt) {
        Timestamp createdTs = Timestamp.from(createdAt.toInstant());
        Timestamp confirmedTs =
                "CONFIRMED".equals(enrollmentStatus) ? createdTs : null;
        return jdbcTemplate.queryForObject(
                "INSERT INTO enrollments (user_id, class_id, status, created_at, confirmed_at) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                userId, classId, enrollmentStatus, createdTs, confirmedTs);
    }
}
