package com.liveclass.registration.controller.dto;

import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import java.time.OffsetDateTime;

/** 수강 신청 응답. 엔티티를 직접 노출하지 않고 식별자/상태/타임스탬프만 전달. */
public record EnrollmentResponse(
        Long enrollmentId,
        Long classId,
        Long userId,
        EnrollmentStatus status,
        OffsetDateTime createdAt
) {

    public static EnrollmentResponse from(Enrollment enrollment) {
        return new EnrollmentResponse(
                enrollment.getId(),
                // LAZY proxy의 getId()는 추가 쿼리 없이 식별자를 반환한다
                enrollment.getCourseClass().getId(),
                enrollment.getUser().getId(),
                enrollment.getStatus(),
                enrollment.getCreatedAt()
        );
    }
}
