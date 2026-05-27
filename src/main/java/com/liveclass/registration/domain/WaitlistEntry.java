package com.liveclass.registration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 대기열 항목. 정원이 가득 찬 강의에 줄 서 있는 한 사용자의 위치를 표현한다.
 *
 * FIFO 순서는 별도 {@code position} 컬럼 없이 {@code (created_at ASC, id ASC)} 정렬로 표현한다.
 * 동일 시각 INSERT 시 {@code id}가 안정적 tie-breaker가 된다.
 *
 * 활성 enrollment와 달리 본 엔티티는 상태 머신이 없다 — 등록 시 INSERT, 이탈·승격 시
 * hard delete. 이력은 승격된 enrollment의 {@code created_at}이 대신한다.
 */
@Entity
@Table(name = "waitlist_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private CourseClass courseClass;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public WaitlistEntry(User user, CourseClass courseClass) {
        this.user = user;
        this.courseClass = courseClass;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WaitlistEntry other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
