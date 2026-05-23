package com.liveclass.registration.domain;

/**
 * 강의 상태. DRAFT → OPEN → CLOSED 단방향.
 *
 * JPA 매핑은 {@code @Enumerated(EnumType.STRING)} — ORDINAL이면
 * DB의 {@code CHECK (status IN ('DRAFT','OPEN','CLOSED'))}가 깨진다.
 */
public enum ClassStatus {
    DRAFT,
    OPEN,
    CLOSED
}
