package com.liveclass.registration.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liveclass.registration.config.EnrollmentExpirationProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EnrollmentExpirationProperties 바인딩 검증 단위 테스트")
class EnrollmentExpirationPropertiesTest {

    @Test
    @DisplayName("유효한 값은 정상 생성된다")
    void validValues_constructSuccessfully() {
        assertThatCode(() -> new EnrollmentExpirationProperties(Duration.ofMinutes(10), 500, true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ttl이 null이면 IllegalArgumentException")
    void nullTtl_throws() {
        assertThatThrownBy(() -> new EnrollmentExpirationProperties(null, 500, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @Test
    @DisplayName("ttl이 0이면 IllegalArgumentException")
    void zeroTtl_throws() {
        assertThatThrownBy(() -> new EnrollmentExpirationProperties(Duration.ZERO, 500, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @Test
    @DisplayName("ttl이 음수이면 IllegalArgumentException")
    void negativeTtl_throws() {
        assertThatThrownBy(() -> new EnrollmentExpirationProperties(Duration.ofSeconds(-1), 500, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @Test
    @DisplayName("batchSize가 0이면 IllegalArgumentException")
    void zeroBatchSize_throws() {
        assertThatThrownBy(() -> new EnrollmentExpirationProperties(Duration.ofMinutes(10), 0, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    @DisplayName("batchSize가 음수이면 IllegalArgumentException")
    void negativeBatchSize_throws() {
        assertThatThrownBy(() -> new EnrollmentExpirationProperties(Duration.ofMinutes(10), -1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }
}
