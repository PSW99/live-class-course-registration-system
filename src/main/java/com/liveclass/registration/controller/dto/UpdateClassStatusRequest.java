package com.liveclass.registration.controller.dto;

import com.liveclass.registration.domain.ClassStatus;
import jakarta.validation.constraints.NotNull;

/** 강의 상태 전이 요청. 도메인 캡슐화에 위임하므로 enum 화이트리스트는 두지 않는다. */
public record UpdateClassStatusRequest(

        @NotNull(message = "status는 필수입니다")
        ClassStatus status
) {
}
