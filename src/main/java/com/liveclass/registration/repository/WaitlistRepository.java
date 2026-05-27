package com.liveclass.registration.repository;

import com.liveclass.registration.domain.WaitlistEntry;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

    /**
     * FIFO 첫 row를 비관적 쓰기 락으로 잠그며 조회. cancel 트랜잭션의 자동 승격 흐름에서 사용한다.
     *
     * {@code Pageable}로 LIMIT 1을 표현해 PostgreSQL이 인덱스 정방향 스캔의 첫 row에만 락을 건다.
     * 호출자는 {@code PageRequest.of(0, 1)}을 넘기고 결과 리스트가 비었는지 확인한다.
     *
     * {@code lock.timeout=3000ms}로 락 대기 무한 블로킹을 방지한다. 명시 JPQL과 함께 정의하지
     * 않으면 Hibernate 버전에 따라 {@code @Lock}이 무시될 수 있으므로 셋 모두 부착한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            select w from WaitlistEntry w
             where w.courseClass.id = :classId
             order by w.createdAt asc, w.id asc
            """)
    List<WaitlistEntry> findFirstByClassIdForUpdate(@Param("classId") Long classId, Pageable pageable);

    /** join 1차 필터 — friendly DUPLICATE_WAITLIST 에러 메시지용. 최종 보증은 DB UNIQUE. */
    boolean existsByCourseClassIdAndUserId(Long classId, Long userId);

    /** leave / findMine — 본인 대기 row 조회. */
    Optional<WaitlistEntry> findByCourseClassIdAndUserId(Long classId, Long userId);

    /** leave 시 본인 row를 잠그며 조회 — promote와 동일 row 경합 시 lock wait 회피. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            select w from WaitlistEntry w
             where w.courseClass.id = :classId and w.user.id = :userId
            """)
    Optional<WaitlistEntry> findByCourseClassIdAndUserIdForUpdate(@Param("classId") Long classId,
                                                                  @Param("userId") Long userId);

    /** join 직후 자신 position 산출용 — 방금 INSERT 한 row가 마지막이므로 자기 포함 총 수가 곧 position. */
    long countByCourseClassId(Long classId);

    /**
     * findMine position 산출 — 본인보다 앞선 대기자 수.
     * 정렬 키 {@code (createdAt ASC, id ASC)}와 일관된 비교 조건으로 카운트한다.
     */
    @Query("""
            select count(w) from WaitlistEntry w
             where w.courseClass.id = :classId
               and (w.createdAt < :createdAt
                    or (w.createdAt = :createdAt and w.id < :id))
            """)
    long countAheadOf(@Param("classId") Long classId,
                      @Param("createdAt") OffsetDateTime createdAt,
                      @Param("id") Long id);
}
