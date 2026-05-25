package com.liveclass.registration.global.exception;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.EnrollmentStatus;

/** 도메인 상태 전이 규칙(단방향)을 어긴 호출에서 발생. */
public class InvalidStatusTransitionException extends DomainException {

    public InvalidStatusTransitionException(Long classId, ClassStatus from, ClassStatus to) {
        super("class %d: %s → %s 전이는 허용되지 않습니다".formatted(classId, from, to));
    }

    public InvalidStatusTransitionException(Long enrollmentId, EnrollmentStatus from, EnrollmentStatus to) {
        super("enrollment %d: %s → %s 전이는 허용되지 않습니다".formatted(enrollmentId, from, to));
    }

    @Override
    public String getCode() {
        return "INVALID_STATUS_TRANSITION";
    }

    @Override
    public int getHttpStatus() {
        return 400;
    }
}
