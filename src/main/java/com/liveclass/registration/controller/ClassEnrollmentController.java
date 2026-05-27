package com.liveclass.registration.controller;

import com.liveclass.registration.controller.dto.ClassEnrollmentResponse;
import com.liveclass.registration.controller.dto.PageQuery;
import com.liveclass.registration.controller.dto.PagedResponse;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.domain.EnrollmentStatus;
import com.liveclass.registration.service.EnrollmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의별 수강생 목록 엔드포인트. URL 계층(/api/classes/{id}/enrollments)을 따르되 응답 도메인이
 * enrollment라 EnrollmentService를 호출하므로 CourseClassController와 분리한다.
 */
@RestController
@RequestMapping("/api/classes/{classId}/enrollments")
@RequiredArgsConstructor
public class ClassEnrollmentController {

    private final EnrollmentService enrollmentService;

    @GetMapping
    public PagedResponse<ClassEnrollmentResponse> listByClass(
            @PathVariable("classId") long classId,
            @RequestHeader("X-User-Id") long requesterId,
            @RequestParam(required = false) EnrollmentStatus status,
            @Valid @ModelAttribute PageQuery query
    ) {
        Page<Enrollment> page = enrollmentService.listByClass(
                classId, requesterId, status, query.toPageable());
        return PagedResponse.of(page, ClassEnrollmentResponse::from);
    }
}
