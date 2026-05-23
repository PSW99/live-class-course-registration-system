package com.liveclass.registration.global.exception;

/** 호출자가 대상 리소스의 소유자가 아닐 때 발생. */
public class ForbiddenAccessException extends DomainException {

    public ForbiddenAccessException(String resourceType, Long resourceId, long requesterId) {
        super("%s %d에 대한 권한이 없습니다 (요청자=%d)".formatted(resourceType, resourceId, requesterId));
    }

    @Override
    public String getCode() {
        return "FORBIDDEN";
    }

    @Override
    public int getHttpStatus() {
        return 403;
    }
}
