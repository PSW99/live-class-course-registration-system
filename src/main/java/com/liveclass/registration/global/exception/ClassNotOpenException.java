package com.liveclass.registration.global.exception;

import com.liveclass.registration.domain.ClassStatus;

/** OPEN 상태가 아닌 강의에 신청 시도 시 발생. */
public class ClassNotOpenException extends DomainException {

    public ClassNotOpenException(Long classId, ClassStatus current) {
        super("class %d: OPEN 상태가 아닙니다 (현재 %s)".formatted(classId, current));
    }

    @Override
    public String getCode() {
        return "CLASS_NOT_OPEN";
    }

    @Override
    public int getHttpStatus() {
        return 400;
    }
}
