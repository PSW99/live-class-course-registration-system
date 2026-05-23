package com.liveclass.registration.controller.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 강의 등록 요청. description은 nullable, 그 외는 필수. */
public record CreateCourseClassRequest(

        @NotBlank(message = "title은 필수입니다")
        @Size(max = 200, message = "title은 200자를 초과할 수 없습니다")
        String title,

        @Size(max = 10000, message = "description은 10000자를 초과할 수 없습니다")
        String description,

        @NotNull(message = "price는 필수입니다")
        @DecimalMin(value = "0", message = "price는 0 이상이어야 합니다")
        @Digits(integer = 10, fraction = 2, message = "price 형식이 올바르지 않습니다")
        BigDecimal price,

        @NotNull(message = "capacity는 필수입니다")
        @Min(value = 1, message = "capacity는 1 이상이어야 합니다")
        Integer capacity,

        @NotNull(message = "startDate는 필수입니다")
        LocalDate startDate,

        @NotNull(message = "endDate는 필수입니다")
        LocalDate endDate
) {

    // jakarta validation으로 표현 불가한 cross-field 검증.
    // Hibernate Validator가 is-prefix boolean 메서드를 자동 호출한다.
    @AssertTrue(message = "endDate는 startDate 이후여야 합니다")
    public boolean isDateOrderValid() {
        if (startDate == null || endDate == null) {
            return true;
        }
        return !endDate.isBefore(startDate);
    }
}
