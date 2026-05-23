package com.liveclass.registration.global.exception;

/** 식별자에 해당하는 리소스가 존재하지 않을 때 발생. */
public class NotFoundException extends DomainException {

    public NotFoundException(String resourceType, Long resourceId) {
        super("%s %d을(를) 찾을 수 없습니다".formatted(resourceType, resourceId));
    }

    @Override
    public String getCode() {
        return "NOT_FOUND";
    }

    @Override
    public int getHttpStatus() {
        return 404;
    }
}
