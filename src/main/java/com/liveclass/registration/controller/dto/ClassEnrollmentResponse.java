package com.liveclass.registration.controller.dto;

import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import java.time.OffsetDateTime;

/**
 * 강의별 수강생 목록 응답. creator의 운영 시각이라 신청자 name·email을 포함한다.
 *
 * 본인 조회용 {@link EnrollmentResponse}와 분리해 개인정보 노출 경로를 DTO 타입으로 격리한다.
 * 다른 엔드포인트가 실수로 user 정보를 노출하는 경로를 만들 수 없게 한다.
 */
public record ClassEnrollmentResponse(
        Long enrollmentId,
        Long userId,
        String userName,
        String userEmail,
        EnrollmentStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt
) {

    public static ClassEnrollmentResponse from(Enrollment enrollment) {
        return new ClassEnrollmentResponse(
                enrollment.getId(),
                enrollment.getUser().getId(),
                enrollment.getUser().getName(),
                enrollment.getUser().getEmail(),
                enrollment.getStatus(),
                enrollment.getCreatedAt(),
                enrollment.getConfirmedAt(),
                enrollment.getCancelledAt()
        );
    }
}
