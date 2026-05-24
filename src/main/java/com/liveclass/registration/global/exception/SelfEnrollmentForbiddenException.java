package com.liveclass.registration.global.exception;

/** 본인이 등록한 강의에 본인이 신청을 시도할 때 발생. */
public class SelfEnrollmentForbiddenException extends DomainException {

    public SelfEnrollmentForbiddenException(long userId, long classId) {
        super("user %d: 본인이 등록한 class %d에 신청할 수 없습니다".formatted(userId, classId));
    }

    @Override
    public String getCode() {
        return "SELF_ENROLLMENT_FORBIDDEN";
    }

    @Override
    public int getHttpStatus() {
        return 400;
    }
}
