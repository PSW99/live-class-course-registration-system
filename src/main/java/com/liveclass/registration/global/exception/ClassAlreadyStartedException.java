package com.liveclass.registration.global.exception;

/** 강의 시작일이 도래해 CONFIRMED enrollment 취소가 불가능할 때 발생. */
public class ClassAlreadyStartedException extends DomainException {

    public ClassAlreadyStartedException(Long enrollmentId) {
        super("enrollment %d: 강의가 이미 시작되어 취소할 수 없습니다".formatted(enrollmentId));
    }

    @Override
    public String getCode() {
        return "CLASS_ALREADY_STARTED";
    }

    @Override
    public int getHttpStatus() {
        return 400;
    }
}
