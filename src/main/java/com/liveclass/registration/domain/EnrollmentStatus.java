package com.liveclass.registration.domain;

/**
 * 수강 신청 상태. PENDING → CONFIRMED → CANCELLED 또는 PENDING → CANCELLED.
 *
 * JPA 매핑은 {@code @Enumerated(EnumType.STRING)} — partial unique index
 * {@code uk_active_enrollment}가 {@code status <> 'CANCELLED'}에 의존한다.
 */
public enum EnrollmentStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
