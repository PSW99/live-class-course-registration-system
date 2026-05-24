package com.liveclass.registration.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 수강 신청 요청. userId는 헤더(X-User-Id)로 받으므로 본문에는 classId만 들어온다. */
public record CreateEnrollmentRequest(

        @NotNull(message = "classId는 필수입니다")
        @Min(value = 1, message = "classId는 양수여야 합니다")
        Long classId
) {
}
