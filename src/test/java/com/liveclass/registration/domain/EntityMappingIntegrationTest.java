package com.liveclass.registration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.EnrollmentRepository;
import com.liveclass.registration.repository.UserRepository;
import com.liveclass.registration.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.spring.starter.RedissonAutoConfiguration;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest(excludeAutoConfiguration = {
        RedissonAutoConfiguration.class,
        RedissonAutoConfigurationV2.class,
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("엔티티 ↔ schema.sql 매핑 통합 테스트")
class EntityMappingIntegrationTest extends PostgresContainerSupport {

    @Autowired
    UserRepository userRepository;

    @Autowired
    CourseClassRepository courseClassRepository;

    @Autowired
    EnrollmentRepository enrollmentRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    @DisplayName("User 저장 후 조회 시 모든 필드가 라운드트립되고 created_at은 DB가 채운다")
    void user_save_and_find_roundtrip() {
        User saved = userRepository.saveAndFlush(new User("Alice", "alice@example.com"));
        entityManager.clear();

        User found = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getName()).isEqualTo("Alice");
        assertThat(found.getEmail()).isEqualTo("alice@example.com");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("CourseClass 저장 시 status는 DRAFT, currentCount는 0으로 초기화되고 ClassStatus는 STRING으로 저장된다")
    void courseClass_default_status_and_string_enum_storage() {
        User creator = userRepository.saveAndFlush(new User("Bob", "bob@example.com"));
        CourseClass courseClass = new CourseClass(
                creator,
                "Spring Boot Deep Dive",
                "동시성 제어 심화",
                new BigDecimal("99000.00"),
                30,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
        );

        CourseClass saved = courseClassRepository.saveAndFlush(courseClass);
        entityManager.clear();

        CourseClass found = courseClassRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ClassStatus.DRAFT);
        assertThat(found.getCurrentCount()).isZero();
        assertThat(found.getCapacity()).isEqualTo(30);
        assertThat(found.getPrice()).isEqualByComparingTo("99000.00");
        assertThat(found.getCreatedAt()).isNotNull();

        Object rawStatus = entityManager
                .createNativeQuery("SELECT status FROM classes WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(rawStatus).isInstanceOf(String.class).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("Enrollment 저장 시 status는 PENDING으로 초기화되고 EnrollmentStatus는 STRING으로 저장된다")
    void enrollment_default_status_and_string_enum_storage() {
        User user = userRepository.saveAndFlush(new User("Carol", "carol@example.com"));
        CourseClass courseClass = courseClassRepository.saveAndFlush(newCourseClass(user, "carol@example.com class"));

        Enrollment saved = enrollmentRepository.saveAndFlush(new Enrollment(user, courseClass));
        entityManager.clear();

        Enrollment found = enrollmentRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getConfirmedAt()).isNull();
        assertThat(found.getCancelledAt()).isNull();

        Object rawStatus = entityManager
                .createNativeQuery("SELECT status FROM enrollments WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(rawStatus).isInstanceOf(String.class).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("OffsetDateTime은 UTC 기준으로 저장·복원된다")
    void offsetDateTime_stored_in_utc() {
        User user = userRepository.saveAndFlush(new User("Dave", "dave@example.com"));
        CourseClass courseClass = courseClassRepository.saveAndFlush(newCourseClass(user, "dave class"));
        Enrollment enrollment = enrollmentRepository.saveAndFlush(new Enrollment(user, courseClass));

        OffsetDateTime cancelledAt = OffsetDateTime.of(2026, 5, 22, 9, 30, 0, 0, ZoneOffset.ofHours(9));
        entityManager
                .createNativeQuery("UPDATE enrollments SET status = 'CANCELLED', cancelled_at = :ts WHERE id = :id")
                .setParameter("ts", cancelledAt)
                .setParameter("id", enrollment.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        Enrollment reloaded = enrollmentRepository.findById(enrollment.getId()).orElseThrow();
        assertThat(reloaded.getCancelledAt()).isNotNull();
        assertThat(reloaded.getCancelledAt().toInstant()).isEqualTo(cancelledAt.toInstant());
        assertThat(reloaded.getCancelledAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("존재하지 않는 user_id로 Enrollment INSERT 시 FK 무결성 위반 예외가 발생한다")
    void enrollment_fk_violation_on_missing_user() {
        User creator = userRepository.saveAndFlush(new User("Eve", "eve@example.com"));
        CourseClass courseClass = courseClassRepository.saveAndFlush(newCourseClass(creator, "eve class"));

        long missingUserId = 9_999_999L;

        assertThatThrownBy(() -> {
            entityManager
                    .createNativeQuery(
                            "INSERT INTO enrollments (user_id, class_id, status) VALUES (:uid, :cid, 'PENDING')")
                    .setParameter("uid", missingUserId)
                    .setParameter("cid", courseClass.getId())
                    .executeUpdate();
            entityManager.flush();
        })
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
                .hasMessageContaining("fk_enrollments_user");
    }

    private CourseClass newCourseClass(User creator, String titleSuffix) {
        return new CourseClass(
                creator,
                "Course for " + titleSuffix,
                "desc",
                new BigDecimal("10000.00"),
                10,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31)
        );
    }
}
