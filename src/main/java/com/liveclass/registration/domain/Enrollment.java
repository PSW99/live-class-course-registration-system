package com.liveclass.registration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import com.liveclass.registration.global.exception.InvalidStatusTransitionException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 수강 신청 이벤트.
 *
 * 활성(PENDING/CONFIRMED) enrollment의 {@code (user, class)} 유일성은
 * partial unique index {@code uk_active_enrollment}로 DB가 강제한다.
 */
@Entity
@Table(name = "enrollments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private CourseClass courseClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EnrollmentStatus status;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    public Enrollment(User user, CourseClass courseClass) {
        this.user = user;
        this.courseClass = courseClass;
        this.status = EnrollmentStatus.PENDING;
    }

    /**
     * PENDING에서만 CONFIRMED로 전이한다. 그 외 상태에서 호출되면
     * {@link InvalidStatusTransitionException}을 throw한다.
     *
     * 트랜잭션 컨텍스트 안에서만 호출되며 dirty checking으로 UPDATE가 flush된다.
     */
    public void confirm() {
        if (this.status != EnrollmentStatus.PENDING) {
            throw new InvalidStatusTransitionException(this.id, this.status, EnrollmentStatus.CONFIRMED);
        }
        this.status = EnrollmentStatus.CONFIRMED;
        this.confirmedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * PENDING 또는 CONFIRMED → CANCELLED로 전이한다. 그 외 상태(이미 CANCELLED)에서
     * 호출되면 {@link InvalidStatusTransitionException}을 throw한다.
     *
     * 시간 기반 정책(취소 가능 기간, 강의 시작 전 여부)은 호출 전 서비스 레이어에서
     * 검증되어야 한다 — 본 메서드는 인자로 받은 timestamp를 그대로 set하고
     * 상태 전이 가드만 수행한다.
     *
     * 트랜잭션 컨텍스트 안에서만 호출되며 dirty checking으로 UPDATE가 flush된다.
     * {@code confirmedAt}은 건드리지 않아 CONFIRMED 이력이 보존된다.
     */
    public void cancel(OffsetDateTime cancelledAt) {
        if (this.status != EnrollmentStatus.PENDING && this.status != EnrollmentStatus.CONFIRMED) {
            throw new InvalidStatusTransitionException(this.id, this.status, EnrollmentStatus.CANCELLED);
        }
        this.status = EnrollmentStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Enrollment other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
