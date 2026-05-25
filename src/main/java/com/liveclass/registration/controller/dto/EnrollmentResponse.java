package com.liveclass.registration.controller.dto;

import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import java.time.OffsetDateTime;

/**
 * 수강 신청 응답. 엔티티를 직접 노출하지 않고 식별자/상태/타임스탬프만 전달.
 *
 * {@code confirmedAt}은 PENDING 상태에서는 {@code null}, CONFIRMED 이후에만 값이 채워진다.
 * {@code cancelledAt}은 CANCELLED 상태에서만 값이 채워진다 — 그 외에는 {@code null}.
 * PENDING에서 곧장 취소된 경우 {@code confirmedAt}은 {@code null}, {@code cancelledAt}만 채워진다.
 */
public record EnrollmentResponse(
        Long enrollmentId,
        Long classId,
        Long userId,
        EnrollmentStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt
) {

    public static EnrollmentResponse from(Enrollment enrollment) {
        return new EnrollmentResponse(
                enrollment.getId(),
                enrollment.getCourseClass().getId(),
                enrollment.getUser().getId(),
                enrollment.getStatus(),
                enrollment.getCreatedAt(),
                enrollment.getConfirmedAt(),
                enrollment.getCancelledAt()
        );
    }
}
