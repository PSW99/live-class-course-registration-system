package com.liveclass.registration.global.exception;

/**
 * 분산락 획득에 실패했을 때 발생. wait timeout 또는 {@code InterruptedException} 변환.
 *
 * 503으로 응답해 "일시적 과부하, 재시도 가능"이라는 표준 의미를 전달한다.
 */
public class LockAcquisitionException extends DomainException {

    public LockAcquisitionException(long classId) {
        super("class %d: 분산락 획득에 실패했습니다 (timeout)".formatted(classId));
    }

    public LockAcquisitionException(long classId, Throwable cause) {
        super("class %d: 분산락 획득 중 인터럽트 발생".formatted(classId), cause);
    }

    @Override
    public String getCode() {
        return "LOCK_ACQUISITION_FAILED";
    }

    @Override
    public int getHttpStatus() {
        return 503;
    }
}
