package com.liveclass.registration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.support.MockRedissonClientConfig;
import com.liveclass.registration.support.PostgresContainerSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
@DisplayName("GET /api/classes — 강의 목록 통합 테스트")
class CourseClassListIntegrationTest extends PostgresContainerSupport {

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
        User creator = userRepository.saveAndFlush(new User("Creator", "creator-list@example.com"));
        creatorId = creator.getId();
    }

    @AfterEach
    void cleanUp() {
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("강의 3개가 있고 기본 페이지(0/20)로 조회하면 200과 함께 정확한 메타·아이템을 반환한다")
    void list_default_returnsAllItemsWithMeta() throws Exception {
        persistClass("Intro A", ClassStatus.DRAFT);
        persistClass("Intro B", ClassStatus.OPEN);
        persistClass("Intro C", ClassStatus.CLOSED);

        mockMvc.perform(get("/api/classes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.content[0].creatorId").value((int) creatorId))
                .andExpect(jsonPath("$.content[0].currentCount").isNumber());
    }

    @Test
    @DisplayName("status=OPEN 필터는 OPEN 강의만 반환한다")
    void list_statusOpenFilter_returnsOnlyOpenClasses() throws Exception {
        persistClass("Draft One", ClassStatus.DRAFT);
        persistClass("Open One", ClassStatus.OPEN);
        persistClass("Open Two", ClassStatus.OPEN);
        persistClass("Closed One", ClassStatus.CLOSED);

        mockMvc.perform(get("/api/classes").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[*].status",
                        containsInAnyOrder("OPEN", "OPEN")))
                .andExpect(jsonPath("$.content[*].title",
                        containsInAnyOrder("Open One", "Open Two")));
    }

    @Test
    @DisplayName("status=DRAFT 필터는 DRAFT 강의만 반환한다")
    void list_statusDraftFilter_returnsOnlyDraftClasses() throws Exception {
        persistClass("Draft Only", ClassStatus.DRAFT);
        persistClass("Open Only", ClassStatus.OPEN);

        mockMvc.perform(get("/api/classes").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Draft Only"))
                .andExpect(jsonPath("$.content[0].status").value("DRAFT"));
    }

    @Test
    @DisplayName("status=CLOSED 필터는 CLOSED 강의만 반환한다")
    void list_statusClosedFilter_returnsOnlyClosedClasses() throws Exception {
        persistClass("Open A", ClassStatus.OPEN);
        persistClass("Closed A", ClassStatus.CLOSED);
        persistClass("Closed B", ClassStatus.CLOSED);

        mockMvc.perform(get("/api/classes").param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].title",
                        containsInAnyOrder("Closed A", "Closed B")));
    }

    @Test
    @DisplayName("status 파라미터를 생략하면 모든 상태의 강의를 반환한다")
    void list_withoutStatus_returnsAllStatuses() throws Exception {
        persistClass("D1", ClassStatus.DRAFT);
        persistClass("O1", ClassStatus.OPEN);
        persistClass("C1", ClassStatus.CLOSED);

        mockMvc.perform(get("/api/classes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].status",
                        containsInAnyOrder("DRAFT", "OPEN", "CLOSED")));
    }

    @Test
    @DisplayName("필터에 매칭되는 강의가 없으면 200과 빈 content, totalElements=0, totalPages=0을 반환한다")
    void list_emptyFilterResult_returnsEmptyContent() throws Exception {
        persistClass("Only Draft", ClassStatus.DRAFT);

        mockMvc.perform(get("/api/classes").param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("page=0, size=10이면 첫 10건을 반환하고 totalPages는 ceil(total/size)이다")
    void list_firstPage_returnsFirstSliceWithCorrectTotalPages() throws Exception {
        for (int i = 0; i < 25; i++) {
            persistClass("C" + i, ClassStatus.OPEN);
        }

        mockMvc.perform(get("/api/classes")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    @DisplayName("마지막 페이지는 잔여 건수만 반환한다")
    void list_lastPage_returnsRemainder() throws Exception {
        for (int i = 0; i < 25; i++) {
            persistClass("C" + i, ClassStatus.OPEN);
        }

        mockMvc.perform(get("/api/classes")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    @DisplayName("범위를 벗어난 페이지(page=99)는 200과 빈 content를 반환한다")
    void list_pageOutOfRange_returnsEmptyContentWithMeta() throws Exception {
        for (int i = 0; i < 3; i++) {
            persistClass("C" + i, ClassStatus.OPEN);
        }

        mockMvc.perform(get("/api/classes")
                        .param("page", "99")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page").value(99))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("size=1은 정상 동작하며 한 건만 반환한다")
    void list_sizeOne_returnsSingleItem() throws Exception {
        persistClass("A", ClassStatus.OPEN);
        persistClass("B", ClassStatus.OPEN);

        mockMvc.perform(get("/api/classes")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("size=100(경계)은 200과 정상 응답을 반환한다")
    void list_sizeAtUpperBound_returnsOk() throws Exception {
        persistClass("Single", ClassStatus.OPEN);

        mockMvc.perform(get("/api/classes")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("size=101은 400 VALIDATION_FAILED를 반환한다")
    void list_sizeOverUpperBound_returnsValidationFailed() throws Exception {
        mockMvc.perform(get("/api/classes")
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
    void list_sizeZero_returnsValidationFailed() throws Exception {
        mockMvc.perform(get("/api/classes")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("size")));
    }

    @Test
    @DisplayName("page=-1은 400 VALIDATION_FAILED를 반환한다")
    void list_negativePage_returnsValidationFailed() throws Exception {
        mockMvc.perform(get("/api/classes")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("page")));
    }

    @Test
    @DisplayName("status=FOO처럼 잘못된 enum 값은 400 INVALID_HEADER를 반환한다")
    void list_invalidStatusEnum_returnsInvalidHeader() throws Exception {
        mockMvc.perform(get("/api/classes")
                        .param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HEADER"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("status")));
    }

    @Test
    @DisplayName("응답 JSON은 정확히 content·page·size·totalElements·totalPages 5개 키만 노출한다")
    void list_responseSchema_hasExactlyFiveKeys() throws Exception {
        persistClass("Schema Check", ClassStatus.OPEN);

        MvcResult result = mockMvc.perform(get("/api/classes"))
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
        assertThat(root.has("unpaged")).isFalse();
        assertThat(root.has("number")).isFalse();
    }

    @Test
    @DisplayName("빈 결과여도 응답 JSON은 동일한 5개 키 셋을 노출한다")
    void list_responseSchema_emptyResultHasSameKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/classes")
                        .param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        List<String> actualKeys = collectFieldNames(root);
        assertThat(actualKeys)
                .containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages");
        assertThat(root.get("content").isArray()).isTrue();
        assertThat(root.get("content").size()).isZero();
        assertThat(root.get("totalElements").asLong()).isZero();
        assertThat(root.get("totalPages").asInt()).isZero();
    }

    private List<String> collectFieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
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
