package com.liveclass.registration.global.exception;

/**
 * 같은 사용자가 같은 강의에 이미 대기 등록된 상태에서 추가 대기 등록을 시도할 때 발생.
 *
 * UNIQUE 제약 {@code uk_waitlist_class_user} 충돌로 잡힌
 * {@code DataIntegrityViolationException}을 변환해 던지거나, 1차 필터 단계에서
 * 친절한 에러로 직접 던진다.
 */
public class DuplicateWaitlistException extends DomainException {

    public DuplicateWaitlistException(long userId, long classId, Throwable cause) {
        super("user %d: class %d에 이미 대기 등록되어 있습니다".formatted(userId, classId), cause);
    }

    public DuplicateWaitlistException(long userId, long classId) {
        this(userId, classId, null);
    }

    @Override
    public String getCode() {
        return "DUPLICATE_WAITLIST";
    }

    @Override
    public int getHttpStatus() {
        return 409;
    }
}
