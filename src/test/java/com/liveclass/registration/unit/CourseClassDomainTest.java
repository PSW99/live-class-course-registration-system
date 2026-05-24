package com.liveclass.registration.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.global.exception.InvalidStatusTransitionException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CourseClass 상태 전이 단위 테스트")
class CourseClassDomainTest {

    @Nested
    @DisplayName("DRAFT 상태에서")
    class FromDraft {

        @Test
        @DisplayName("open() 호출은 OPEN으로 전이된다")
        void open_succeeds() {
            CourseClass courseClass = newDraftClass();

            courseClass.open();

            assertThat(courseClass.getStatus()).isEqualTo(ClassStatus.OPEN);
        }

        @Test
        @DisplayName("close() 호출은 InvalidStatusTransitionException을 던진다")
        void close_throws() {
            CourseClass courseClass = newDraftClass();

            assertThatThrownBy(courseClass::close)
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("DRAFT")
                    .hasMessageContaining("CLOSED");
            assertThat(courseClass.getStatus()).isEqualTo(ClassStatus.DRAFT);
        }
    }

    @Nested
    @DisplayName("OPEN 상태에서")
    class FromOpen {

        @Test
        @DisplayName("open() 재호출은 InvalidStatusTransitionException을 던진다")
        void open_throws() {
            CourseClass courseClass = newDraftClass();
            courseClass.open();

            assertThatThrownBy(courseClass::open)
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("OPEN");
            assertThat(courseClass.getStatus()).isEqualTo(ClassStatus.OPEN);
        }

        @Test
        @DisplayName("close() 호출은 CLOSED로 전이된다")
        void close_succeeds() {
            CourseClass courseClass = newDraftClass();
            courseClass.open();

            courseClass.close();

            assertThat(courseClass.getStatus()).isEqualTo(ClassStatus.CLOSED);
        }
    }

    @Nested
    @DisplayName("CLOSED 상태에서")
    class FromClosed {

        @Test
        @DisplayName("open() 호출은 InvalidStatusTransitionException을 던진다")
        void open_throws() {
            CourseClass courseClass = closedClass();

            assertThatThrownBy(courseClass::open)
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("CLOSED")
                    .hasMessageContaining("OPEN");
            assertThat(courseClass.getStatus()).isEqualTo(ClassStatus.CLOSED);
        }

        @Test
        @DisplayName("close() 재호출은 InvalidStatusTransitionException을 던진다")
        void close_throws() {
            CourseClass courseClass = closedClass();

            assertThatThrownBy(courseClass::close)
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("CLOSED");
            assertThat(courseClass.getStatus()).isEqualTo(ClassStatus.CLOSED);
        }
    }

    @Test
    @DisplayName("새로 생성된 CourseClass는 DRAFT 상태이고 currentCount는 0이다")
    void newCourseClass_initialState() {
        CourseClass courseClass = newDraftClass();

        assertThat(courseClass.getStatus()).isEqualTo(ClassStatus.DRAFT);
        assertThat(courseClass.getCurrentCount()).isZero();
    }

    @Test
    @DisplayName("DRAFT → OPEN → CLOSED 순방향 시퀀스는 모두 성공한다")
    void forwardSequence_succeeds() {
        CourseClass courseClass = newDraftClass();

        courseClass.open();
        assertThat(courseClass.getStatus()).isEqualTo(ClassStatus.OPEN);

        courseClass.close();
        assertThat(courseClass.getStatus()).isEqualTo(ClassStatus.CLOSED);
    }

    private static CourseClass newDraftClass() {
        return new CourseClass(
                new User("Creator", "creator@example.com"),
                "Sample Class",
                "desc",
                new BigDecimal("10000.00"),
                10,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
        );
    }

    private static CourseClass closedClass() {
        CourseClass courseClass = newDraftClass();
        courseClass.open();
        courseClass.close();
        return courseClass;
    }
}
