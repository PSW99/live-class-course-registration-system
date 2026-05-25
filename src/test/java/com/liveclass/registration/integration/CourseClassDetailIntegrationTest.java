package com.liveclass.registration.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.support.MockRedissonClientConfig;
import com.liveclass.registration.support.PostgresContainerSupport;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
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
@DisplayName("GET /api/classes/{id} — 강의 상세 통합 테스트")
class CourseClassDetailIntegrationTest extends PostgresContainerSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CourseClassRepository courseClassRepository;

    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private long creatorId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        User creator = userRepository.saveAndFlush(new User("Creator", "creator-detail@example.com"));
        creatorId = creator.getId();
    }

    @AfterEach
    void cleanUp() {
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("currentCount=0인 신규 강의를 상세 조회하면 200과 currentCount=0을 반환한다")
    void detail_freshClass_returnsZeroCurrentCount() throws Exception {
        long classId = persistClass("Detail Fresh", ClassStatus.OPEN);

        mockMvc.perform(get("/api/classes/{id}", classId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) classId))
                .andExpect(jsonPath("$.creatorId").value((int) creatorId))
                .andExpect(jsonPath("$.title").value("Detail Fresh"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.capacity").value(10))
                .andExpect(jsonPath("$.currentCount").value(0))
                .andExpect(jsonPath("$.startDate").value("2026-06-01"))
                .andExpect(jsonPath("$.endDate").value("2026-06-30"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("currentCount>0인 강의를 상세 조회하면 해당 값이 반환된다")
    void detail_classWithPositiveCount_returnsActualCount() throws Exception {
        long classId = persistClass("Detail With Count", ClassStatus.OPEN);
        jdbcTemplate.update("UPDATE classes SET current_count = ? WHERE id = ?", 4, classId);

        mockMvc.perform(get("/api/classes/{id}", classId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) classId))
                .andExpect(jsonPath("$.currentCount").value(4))
                .andExpect(jsonPath("$.capacity").value(10));
    }

    @Test
    @DisplayName("존재하지 않는 강의 id로 조회하면 404 NOT_FOUND를 반환한다")
    void detail_unknownId_returnsNotFound() throws Exception {
        long missingId = 9_999_999L;

        mockMvc.perform(get("/api/classes/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message", containsString("class")));
    }

    @Transactional
    long persistClass(String title, ClassStatus targetStatus) {
        User owner = userRepository.findById(creatorId).orElseThrow();
        CourseClass cls = new CourseClass(
                owner,
                title,
                "desc",
                new BigDecimal("10000.00"),
                10,
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
        courseClassRepository.saveAndFlush(cls);
        return cls.getId();
    }
}
