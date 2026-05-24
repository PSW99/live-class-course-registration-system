package com.liveclass.registration.global.exception;

/**
 * 같은 사용자가 같은 강의에 이미 활성(PENDING/CONFIRMED) 신청을 가진 상태에서
 * 추가 신청을 시도할 때 발생.
 *
 * partial unique index {@code uk_active_enrollment} 충돌로 잡힌
 * {@code DataIntegrityViolationException}을 변환해 던진다.
 */
public class DuplicateEnrollmentException extends DomainException {

    public DuplicateEnrollmentException(long userId, long classId, Throwable cause) {
        super("user %d: class %d에 이미 활성 신청이 존재합니다".formatted(userId, classId), cause);
    }

    public DuplicateEnrollmentException(long userId, long classId) {
        this(userId, classId, null);
    }

    @Override
    public String getCode() {
        return "DUPLICATE_ENROLLMENT";
    }

    @Override
    public int getHttpStatus() {
        return 409;
    }
}
