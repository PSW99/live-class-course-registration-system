package com.liveclass.registration.global;

/** 모든 에러 응답이 공유하는 표준 페이로드. */
public record ErrorResponse(String code, String message) {
}
