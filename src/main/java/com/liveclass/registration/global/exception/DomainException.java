package com.liveclass.registration.global.exception;

/**
 * 도메인 규칙 위반을 표현하는 공통 부모 예외.
 *
 * 모든 도메인 예외는 자기 자신의 응답 code와 HTTP status를 보유한다.
 * GlobalExceptionHandler는 본 추상 타입 하나만 보고 응답을 만들 수 있다.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    public abstract String getCode();

    public abstract int getHttpStatus();
}
