package com.liveclass.registration.repository;

import com.liveclass.registration.domain.CourseClass;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface CourseClassRepository extends JpaRepository<CourseClass, Long> {

    /**
     * 강의 row를 비관적 쓰기 락으로 잠그면서 조회. PostgreSQL에서 {@code SELECT ... FOR UPDATE}로 변환된다.
     *
     * {@code lock.timeout=3000ms}로 락 대기 무한 블로킹을 방지한다. 명시 JPQL과 함께 정의하지 않으면
     * Hibernate 버전에 따라 {@code @Lock}이 무시될 수 있으므로 셋 모두 부착한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select c from CourseClass c where c.id = :id")
    Optional<CourseClass> findByIdForUpdate(@Param("id") Long id);
}
