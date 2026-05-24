package com.liveclass.registration.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.support.MockRedissonClientConfig;
import com.liveclass.registration.support.PostgresContainerSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
@DisplayName("POST /api/classes — 강의 등록 통합 테스트")
class CourseClassCreateIntegrationTest extends PostgresContainerSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CourseClassRepository courseClassRepository;

    private long creatorId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.saveAndFlush(new User("Creator", "creator@example.com"));
        creatorId = creator.getId();
    }

    @AfterEach
    void cleanUp() {
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("정상 요청은 201 Created와 함께 DRAFT 상태의 강의를 반환한다")
    void create_success() throws Exception {
        Map<String, Object> body = validBody();

        mockMvc.perform(post("/api/classes")
                        .header("X-User-Id", creatorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.creatorId").value((int) creatorId))
                .andExpect(jsonPath("$.title").value("Spring Boot 동시성 마스터"))
                .andExpect(jsonPath("$.description").value("분산락 실습"))
                .andExpect(jsonPath("$.price").value(99000))
                .andExpect(jsonPath("$.capacity").value(30))
                .andExpect(jsonPath("$.currentCount").value(0))
                .andExpect(jsonPath("$.startDate").value("2026-06-01"))
                .andExpect(jsonPath("$.endDate").value("2026-06-30"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("title이 빈 문자열이면 400 VALIDATION_FAILED를 반환한다")
    void create_blankTitle_returns400() throws Exception {
        Map<String, Object> body = validBody();
        body.put("title", "");

        mockMvc.perform(post("/api/classes")
                        .header("X-User-Id", creatorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("title")));
    }

    @Test
    @DisplayName("capacity가 0 이하이면 400 VALIDATION_FAILED를 반환한다")
    void create_zeroCapacity_returns400() throws Exception {
        Map<String, Object> body = validBody();
        body.put("capacity", 0);

        mockMvc.perform(post("/api/classes")
                        .header("X-User-Id", creatorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("capacity")));
    }

    @Test
    @DisplayName("price가 음수이면 400 VALIDATION_FAILED를 반환한다")
    void create_negativePrice_returns400() throws Exception {
        Map<String, Object> body = validBody();
        body.put("price", new BigDecimal("-1"));

        mockMvc.perform(post("/api/classes")
                        .header("X-User-Id", creatorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("price")));
    }

    @Test
    @DisplayName("endDate가 startDate보다 이전이면 400 VALIDATION_FAILED를 반환한다")
    void create_endBeforeStart_returns400() throws Exception {
        Map<String, Object> body = validBody();
        body.put("startDate", LocalDate.of(2026, 7, 1).toString());
        body.put("endDate", LocalDate.of(2026, 6, 30).toString());

        mockMvc.perform(post("/api/classes")
                        .header("X-User-Id", creatorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("endDate")));
    }

    @Test
    @DisplayName("필수 필드(title)가 누락되면 400 VALIDATION_FAILED를 반환한다")
    void create_missingTitle_returns400() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("title");

        mockMvc.perform(post("/api/classes")
                        .header("X-User-Id", creatorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("title")));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 없으면 400 MISSING_HEADER를 반환한다")
    void create_missingHeader_returns400() throws Exception {
        Map<String, Object> body = validBody();

        mockMvc.perform(post("/api/classes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.message", containsString("X-User-Id")));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 숫자가 아니면 400 INVALID_HEADER를 반환한다")
    void create_nonNumericHeader_returns400() throws Exception {
        Map<String, Object> body = validBody();

        mockMvc.perform(post("/api/classes")
                        .header("X-User-Id", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HEADER"));
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Spring Boot 동시성 마스터");
        body.put("description", "분산락 실습");
        body.put("price", new BigDecimal("99000"));
        body.put("capacity", 30);
        body.put("startDate", LocalDate.of(2026, 6, 1).toString());
        body.put("endDate", LocalDate.of(2026, 6, 30).toString());
        return body;
    }
}
