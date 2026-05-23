package com.liveclass.registration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.support.PostgresContainerSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.spring.starter.RedissonAutoConfiguration;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(excludeAutoConfiguration = {
        RedissonAutoConfiguration.class,
        RedissonAutoConfigurationV2.class,
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("partial unique index uk_active_enrollment 동작 검증")
class EnrollmentActiveUniqueConstraintTest extends PostgresContainerSupport {

    @Autowired
    UserRepository userRepository;

    @Autowired
    CourseClassRepository courseClassRepository;

    @Autowired
    EnrollmentRepository enrollmentRepository;

    @Autowired
    DataSource dataSource;

    JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        enrollmentRepository.deleteAllInBatch();
        courseClassRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("동일 user/class 활성 PENDING 두 번째 저장은 uk_active_enrollment 위반으로 차단된다")
    void activeDuplicate_isRejectedByPartialUniqueIndex() {
        User user = userRepository.saveAndFlush(new User("Frank", "frank-a@example.com"));
        CourseClass courseClass = courseClassRepository.saveAndFlush(newCourseClass(user));

        enrollmentRepository.saveAndFlush(new Enrollment(user, courseClass));

        Enrollment duplicate = new Enrollment(user, courseClass);

        assertThatThrownBy(() -> enrollmentRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> {
                    String message = rootMessage(ex);
                    assertThat(message).contains("uk_active_enrollment");
                });

        assertThat(enrollmentRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("기존 활성 enrollment를 CANCELLED로 전이한 뒤 같은 user/class 재신청은 허용된다")
    void cancelledRow_allowsNewActiveEnrollment() {
        User user = userRepository.saveAndFlush(new User("Grace", "grace-b@example.com"));
        CourseClass courseClass = courseClassRepository.saveAndFlush(newCourseClass(user));

        Enrollment first = enrollmentRepository.saveAndFlush(new Enrollment(user, courseClass));

        int updated = jdbcTemplate().update(
                "UPDATE enrollments SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant()),
                first.getId());
        assertThat(updated).isEqualTo(1);

        Enrollment second = enrollmentRepository.saveAndFlush(new Enrollment(user, courseClass));

        assertThat(second.getId()).isNotNull().isNotEqualTo(first.getId());
        assertThat(enrollmentRepository.count()).isEqualTo(2);
    }

    private JdbcTemplate jdbcTemplate() {
        if (jdbcTemplate == null) {
            jdbcTemplate = new JdbcTemplate(dataSource);
        }
        return jdbcTemplate;
    }

    private CourseClass newCourseClass(User creator) {
        return new CourseClass(
                creator,
                "Concurrency Class",
                "concurrency",
                new BigDecimal("50000.00"),
                10,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)
        );
    }

    private static String rootMessage(Throwable t) {
        Throwable cursor = t;
        StringBuilder sb = new StringBuilder();
        while (cursor != null) {
            if (cursor.getMessage() != null) {
                sb.append(cursor.getMessage()).append('\n');
            }
            cursor = cursor.getCause();
        }
        return sb.toString();
    }
}
