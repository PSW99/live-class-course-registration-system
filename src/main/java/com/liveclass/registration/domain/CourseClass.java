package com.liveclass.registration.domain;

import com.liveclass.registration.global.exception.CapacityExceededException;
import com.liveclass.registration.global.exception.InvalidStatusTransitionException;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 강의. {@code java.lang.Class}와 혼동을 피해 클래스명은 {@code CourseClass},
 * 테이블명은 {@code classes}로 분리한다.
 *
 * {@code currentCount}는 활성 enrollment(PENDING+CONFIRMED) 수의 denormalized 값.
 */
@Entity
@Table(name = "classes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "current_count", nullable = false)
    private Integer currentCount;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ClassStatus status;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public CourseClass(
            User creator,
            String title,
            String description,
            BigDecimal price,
            Integer capacity,
            LocalDate startDate,
            LocalDate endDate
    ) {
        this.creator = creator;
        this.title = title;
        this.description = description;
        this.price = price;
        this.capacity = capacity;
        this.currentCount = 0;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = ClassStatus.DRAFT;
    }

    /** DRAFT에서만 OPEN으로 전이. 그 외 상태에서 호출되면 예외. */
    public void open() {
        if (this.status != ClassStatus.DRAFT) {
            throw new InvalidStatusTransitionException(this.id, this.status, ClassStatus.OPEN);
        }
        this.status = ClassStatus.OPEN;
    }

    /** OPEN에서만 CLOSED로 전이. 그 외 상태에서 호출되면 예외. */
    public void close() {
        if (this.status != ClassStatus.OPEN) {
            throw new InvalidStatusTransitionException(this.id, this.status, ClassStatus.CLOSED);
        }
        this.status = ClassStatus.CLOSED;
    }

    /**
     * 활성 신청 수를 1 증가시킨다.
     *
     * capacity에 도달한 상태에서 호출되면 {@link CapacityExceededException}을 던진다.
     * 호출자는 비관적 락이 잡힌 row 위에서만 이 메서드를 호출해야 한다 —
     * 그래야 read-modify-write가 원자적이다.
     */
    public void incrementCurrentCount() {
        if (this.currentCount >= this.capacity) {
            throw new CapacityExceededException(this.id, this.capacity);
        }
        this.currentCount = this.currentCount + 1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CourseClass other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
