package com.liveclass.registration.global.exception;

/**
 * 같은 사용자가 같은 강의에 이미 활성(PENDING/CONFIRMED) 신청을 가진 상태에서
 * 대기 등록을 시도할 때 발생.
 *
 * 한 사용자는 같은 강의에 대해 enrollment 또는 waitlist 둘 중 하나만 보유할 수 있다.
 */
public class ActiveEnrollmentExistsException extends DomainException {

    public ActiveEnrollmentExistsException(long userId, long classId) {
        super("user %d: class %d에 이미 활성 신청이 존재합니다 (대기 등록 불가)".formatted(userId, classId));
    }

    @Override
    public String getCode() {
        return "ACTIVE_ENROLLMENT_EXISTS";
    }

    @Override
    public int getHttpStatus() {
        return 409;
    }
}
