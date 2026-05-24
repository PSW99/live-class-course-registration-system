package com.liveclass.registration.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveclass.registration.controller.dto.CreateCourseClassRequest;
import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.service.CourseClassService;
import com.liveclass.registration.support.MockRedissonClientConfig;
import com.liveclass.registration.support.PostgresContainerSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
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
@DisplayName("PATCH /api/classes/{id}/status — 강의 상태 전이 통합 테스트")
class CourseClassStatusChangeIntegrationTest extends PostgresContainerSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CourseClassRepository courseClassRepository;

    @Autowired
    CourseClassService courseClassService;

    private long ownerId;
    private long strangerId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.saveAndFlush(new User("Owner", "owner@example.com"));
        User stranger = userRepository.saveAndFlush(new User("Stranger", "stranger@example.com"));
        ownerId = owner.getId();
        strangerId = stranger.getId();
    }

    @AfterEach
    void cleanUp() {
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("소유자의 DRAFT → OPEN 요청은 200과 변경된 상태를 반환한다")
    void draftToOpen_succeeds() throws Exception {
        long classId = createDraftClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) classId))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("소유자의 OPEN → CLOSED 요청은 200과 변경된 상태를 반환한다")
    void openToClosed_succeeds() throws Exception {
        long classId = createOpenClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "CLOSED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    @DisplayName("DRAFT 상태에서 CLOSED 요청은 400 INVALID_STATUS_TRANSITION을 반환한다")
    void draftToClosed_returnsInvalidTransition() throws Exception {
        long classId = createDraftClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "CLOSED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message", containsString("DRAFT")))
                .andExpect(jsonPath("$.message", containsString("CLOSED")));
    }

    @Test
    @DisplayName("OPEN 상태에서 OPEN 재요청은 400 INVALID_STATUS_TRANSITION을 반환한다")
    void openToOpen_returnsInvalidTransition() throws Exception {
        long classId = createOpenClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("CLOSED 상태에서 OPEN 요청은 400 INVALID_STATUS_TRANSITION을 반환한다")
    void closedToOpen_returnsInvalidTransition() throws Exception {
        long classId = createClosedClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("CLOSED 상태에서 CLOSED 재요청은 400 INVALID_STATUS_TRANSITION을 반환한다")
    void closedToClosed_returnsInvalidTransition() throws Exception {
        long classId = createClosedClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "CLOSED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("DRAFT enum 입력은 소유자 요청이라도 400 INVALID_STATUS_TRANSITION을 반환한다")
    void draftEnumInput_returnsInvalidTransition() throws Exception {
        long classId = createDraftClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DRAFT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("타인이 다른 사람의 강의 상태를 변경하려고 하면 403 FORBIDDEN을 반환한다")
    void otherUser_returnsForbidden() throws Exception {
        long classId = createDraftClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", strangerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("존재하지 않는 강의 id에 대한 요청은 404 NOT_FOUND를 반환한다")
    void unknownClassId_returnsNotFound() throws Exception {
        long missingId = 9_999_999L;

        mockMvc.perform(patch("/api/classes/{id}/status", missingId)
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message", containsString("class")));
    }

    @Test
    @DisplayName("존재하지 않는 강의 id + 타인 헤더의 조합도 403이 아닌 404 NOT_FOUND를 반환한다")
    void unknownClassId_withStrangerHeader_prefersNotFound() throws Exception {
        long missingId = 9_999_999L;

        mockMvc.perform(patch("/api/classes/{id}/status", missingId)
                        .header("X-User-Id", strangerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
    void missingHeader_returns400() throws Exception {
        long classId = createDraftClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.message", containsString("X-User-Id")));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 숫자가 아니면 400 INVALID_HEADER를 반환한다")
    void nonNumericHeader_returns400() throws Exception {
        long classId = createDraftClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", "not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HEADER"));
    }

    @Test
    @DisplayName("정의되지 않은 enum 값을 보내면 400 MALFORMED_JSON을 반환한다")
    void invalidEnumValue_returnsMalformedJson() throws Exception {
        long classId = createDraftClass();

        mockMvc.perform(patch("/api/classes/{id}/status", classId)
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INVALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    private long createDraftClass() {
        CreateCourseClassRequest req = new CreateCourseClassRequest(
                "Sample Class",
                "desc",
                new BigDecimal("10000.00"),
                10,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
        );
        CourseClass created = courseClassService.create(ownerId, req);
        return created.getId();
    }

    private long createOpenClass() {
        long classId = createDraftClass();
        courseClassService.changeStatus(classId, ownerId, ClassStatus.OPEN);
        return classId;
    }

    private long createClosedClass() {
        long classId = createOpenClass();
        courseClassService.changeStatus(classId, ownerId, ClassStatus.CLOSED);
        return classId;
    }
}
