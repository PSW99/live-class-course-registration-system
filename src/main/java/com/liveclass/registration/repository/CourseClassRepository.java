package com.liveclass.registration.repository;

import com.liveclass.registration.domain.CourseClass;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseClassRepository extends JpaRepository<CourseClass, Long> {
}
