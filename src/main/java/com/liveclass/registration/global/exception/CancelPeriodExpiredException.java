package com.liveclass.registration.global.exception;

/** CONFIRMED enrollment의 취소 가능 기간(결제 후 7일)이 경과했을 때 발생. */
public class CancelPeriodExpiredException extends DomainException {

    public CancelPeriodExpiredException(Long enrollmentId) {
        super("enrollment %d: 결제 후 7일이 경과해 취소할 수 없습니다".formatted(enrollmentId));
    }

    @Override
    public String getCode() {
        return "CANCEL_PERIOD_EXPIRED";
    }

    @Override
    public int getHttpStatus() {
        return 400;
    }
}
