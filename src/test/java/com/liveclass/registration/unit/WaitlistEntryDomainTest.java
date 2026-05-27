package com.liveclass.registration.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.domain.WaitlistEntry;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WaitlistEntry 단위 테스트")
class WaitlistEntryDomainTest {

    @Test
    @DisplayName("새로 생성된 WaitlistEntry는 생성자에 넘긴 user와 courseClass를 그대로 노출한다")
    void newEntry_holdsConstructorArguments() {
        User creator = new User("Creator", "creator-waitlist-domain@example.com");
        User learner = new User("Learner", "learner-waitlist-domain@example.com");
        CourseClass courseClass = newCourseClass(creator);

        WaitlistEntry entry = new WaitlistEntry(learner, courseClass);

        assertThat(entry.getUser()).isSameAs(learner);
        assertThat(entry.getCourseClass()).isSameAs(courseClass);
        assertThat(entry.getId()).isNull();
        assertThat(entry.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("id가 같으면 equals는 true이고 hashCode도 일치한다")
    void equalsAndHashCode_basedOnId() throws Exception {
        User creator = new User("Creator", "creator-eq@example.com");
        User learner = new User("Learner", "learner-eq@example.com");
        CourseClass courseClass = newCourseClass(creator);

        WaitlistEntry left = new WaitlistEntry(learner, courseClass);
        WaitlistEntry right = new WaitlistEntry(learner, courseClass);
        setId(left, 42L);
        setId(right, 42L);

        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
    }

    @Test
    @DisplayName("id가 다르면 equals는 false이다")
    void equals_differentId_returnsFalse() throws Exception {
        User creator = new User("Creator", "creator-neq@example.com");
        User learner = new User("Learner", "learner-neq@example.com");
        CourseClass courseClass = newCourseClass(creator);

        WaitlistEntry left = new WaitlistEntry(learner, courseClass);
        WaitlistEntry right = new WaitlistEntry(learner, courseClass);
        setId(left, 1L);
        setId(right, 2L);

        assertThat(left).isNotEqualTo(right);
    }

    @Test
    @DisplayName("id가 null인 두 인스턴스는 equals가 false이다")
    void equals_bothIdNull_returnsFalse() {
        User creator = new User("Creator", "creator-null@example.com");
        User learner = new User("Learner", "learner-null@example.com");
        CourseClass courseClass = newCourseClass(creator);

        WaitlistEntry left = new WaitlistEntry(learner, courseClass);
        WaitlistEntry right = new WaitlistEntry(learner, courseClass);

        assertThat(left).isNotEqualTo(right);
    }

    @Test
    @DisplayName("자기 자신과 비교하면 equals는 true이다")
    void equals_sameInstance_returnsTrue() {
        User creator = new User("Creator", "creator-self@example.com");
        User learner = new User("Learner", "learner-self@example.com");
        WaitlistEntry entry = new WaitlistEntry(learner, newCourseClass(creator));

        assertThat(entry).isEqualTo(entry);
    }

    private static CourseClass newCourseClass(User creator) {
        return new CourseClass(
                creator,
                "Sample Class",
                "desc",
                new BigDecimal("10000.00"),
                10,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
        );
    }

    private static void setId(WaitlistEntry entry, Long id) throws Exception {
        Field field = WaitlistEntry.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entry, id);
    }
}
