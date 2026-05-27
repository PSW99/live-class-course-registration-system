package com.liveclass.registration.global.exception;

/**
 * 정원에 자리가 남은 강의에 대해 대기 등록을 시도할 때 발생.
 *
 * 대기 등록은 정원이 가득 찬 강의에서만 허용된다. 자리가 있다면 사용자는
 * 대기열이 아닌 일반 신청 흐름을 사용해야 한다.
 */
public class CapacityAvailableException extends DomainException {

    public CapacityAvailableException(long classId) {
        super("class %d: 정원에 자리가 있습니다. 대기열 대신 직접 신청해 주세요".formatted(classId));
    }

    @Override
    public String getCode() {
        return "CAPACITY_AVAILABLE";
    }

    @Override
    public int getHttpStatus() {
        return 400;
    }
}
