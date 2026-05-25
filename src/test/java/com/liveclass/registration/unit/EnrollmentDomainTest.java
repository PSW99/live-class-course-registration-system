package com.liveclass.registration.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.global.exception.InvalidStatusTransitionException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Enrollment 상태 전이 단위 테스트")
class EnrollmentDomainTest {

    @Nested
    @DisplayName("PENDING 상태에서")
    class FromPending {

        @Test
        @DisplayName("confirm() 호출은 CONFIRMED로 전이되고 confirmedAt이 설정된다")
        void confirm_succeeds() {
            Enrollment enrollment = newPendingEnrollment();
            OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

            enrollment.confirm();

            OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);
            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
            assertThat(enrollment.getConfirmedAt())
                    .isNotNull()
                    .isBetween(before, after);
        }

        @Test
        @DisplayName("confirm() 호출 후 confirmedAt 오프셋은 UTC로 고정된다")
        void confirm_confirmedAtUsesUtcOffset() {
            Enrollment enrollment = newPendingEnrollment();

            enrollment.confirm();

            assertThat(enrollment.getConfirmedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        }
    }

    @Nested
    @DisplayName("CONFIRMED 상태에서")
    class FromConfirmed {

        @Test
        @DisplayName("confirm() 재호출은 InvalidStatusTransitionException을 던진다")
        void confirm_throws() {
            Enrollment enrollment = newPendingEnrollment();
            enrollment.confirm();

            assertThatThrownBy(enrollment::confirm)
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("enrollment")
                    .hasMessageContaining("CONFIRMED");
            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        }

        @Test
        @DisplayName("재호출 예외의 code와 httpStatus는 INVALID_STATUS_TRANSITION / 400이다")
        void confirm_throws_codeAndStatus() {
            Enrollment enrollment = newPendingEnrollment();
            enrollment.confirm();

            assertThatThrownBy(enrollment::confirm)
                    .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo("INVALID_STATUS_TRANSITION");
                        assertThat(ex.getHttpStatus()).isEqualTo(400);
                    });
        }
    }

    @Test
    @DisplayName("새로 생성된 Enrollment는 PENDING 상태이고 confirmedAt은 null이다")
    void newEnrollment_initialState() {
        Enrollment enrollment = newPendingEnrollment();

        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(enrollment.getConfirmedAt()).isNull();
    }

    private static Enrollment newPendingEnrollment() {
        User creator = new User("Creator", "creator-domain@example.com");
        User learner = new User("Learner", "learner-domain@example.com");
        CourseClass courseClass = new CourseClass(
                creator,
                "Sample Class",
                "desc",
                new BigDecimal("10000.00"),
                10,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
        );
        return new Enrollment(learner, courseClass);
    }
}
