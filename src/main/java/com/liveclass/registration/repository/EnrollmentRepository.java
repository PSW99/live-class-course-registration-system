package com.liveclass.registration.repository;

import com.liveclass.registration.domain.Enrollment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    /**
     * enrollment row를 비관적 쓰기 락으로 잠그면서 조회. 동일 enrollment에 대한 동시 상태 전이
     * (특히 cancel 더블 탭)에서 stale 상태로 정원이 이중 감소되는 것을 막는다.
     *
     * cancel 흐름에서는 enrollment → class 순으로 락을 잡는다. enroll은 기존 enrollment row를
     * 잠그지 않으므로 순서 충돌이 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select e from Enrollment e where e.id = :id")
    Optional<Enrollment> findByIdForUpdate(@Param("id") Long id);

    /**
     * 특정 사용자의 enrollment를 최신순으로 페이지 조회.
     * {@code idx_enrollments_user_created_at (user_id, created_at DESC)} 인덱스가
     * 필터·정렬을 단일 index scan으로 처리한다.
     */
    Page<Enrollment> findByUserIdOrderByCreatedAtDesc(long userId, Pageable pageable);

    /**
     * 같은 사용자의 같은 강의에 활성(PENDING/CONFIRMED) enrollment가 존재하는지 확인.
     *
     * 대기 등록 시 cross-table 정합성 1차 필터로 사용된다. 본 검증은 friendly 에러용이며
     * 최종 보증은 partial unique index {@code uk_active_enrollment}가 담당한다.
     */
    @Query("""
            select case when count(e) > 0 then true else false end
              from Enrollment e
             where e.user.id = :userId
               and e.courseClass.id = :classId
               and e.status <> com.liveclass.registration.domain.EnrollmentStatus.CANCELLED
            """)
    boolean existsActiveByUserIdAndClassId(@Param("userId") Long userId, @Param("classId") Long classId);

    /** TTL 경과 PENDING 후보 ID. 락 없이 후보만 수집 — 본 만료는 per-row REQUIRES_NEW에서 처리. */
    @Query("""
            select e.id from Enrollment e
             where e.status = com.liveclass.registration.domain.EnrollmentStatus.PENDING
               and e.createdAt <= :threshold
             order by e.createdAt asc, e.id asc
            """)
    List<Long> findExpiredPendingIds(@Param("threshold") OffsetDateTime threshold, Pageable pageable);
}
