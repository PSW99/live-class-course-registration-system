package com.liveclass.registration.global.exception;

/** 정원이 마감된 강의에 추가 신청이 들어왔을 때 발생. */
public class CapacityExceededException extends DomainException {

    public CapacityExceededException(Long classId, Integer capacity) {
        super("class %d: 정원(%d)이 마감되었습니다".formatted(classId, capacity));
    }

    @Override
    public String getCode() {
        return "CAPACITY_EXCEEDED";
    }

    @Override
    public int getHttpStatus() {
        return 409;
    }
}
