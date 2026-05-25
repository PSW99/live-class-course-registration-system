package com.liveclass.registration.controller;

import com.liveclass.registration.controller.dto.CourseClassResponse;
import com.liveclass.registration.controller.dto.CreateCourseClassRequest;
import com.liveclass.registration.controller.dto.PageQuery;
import com.liveclass.registration.controller.dto.PagedResponse;
import com.liveclass.registration.controller.dto.UpdateClassStatusRequest;
import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.service.CourseClassService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 강의 등록·상태 전이·조회 엔드포인트. */
@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
public class CourseClassController {

    private final CourseClassService courseClassService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseClassResponse create(
            @RequestHeader("X-User-Id") long requesterId,
            @Valid @RequestBody CreateCourseClassRequest request
    ) {
        CourseClass cls = courseClassService.create(requesterId, request);
        return CourseClassResponse.from(cls);
    }

    @PatchMapping("/{id}/status")
    public CourseClassResponse changeStatus(
            @PathVariable("id") long classId,
            @RequestHeader("X-User-Id") long requesterId,
            @Valid @RequestBody UpdateClassStatusRequest request
    ) {
        CourseClass cls = courseClassService.changeStatus(classId, requesterId, request.status());
        return CourseClassResponse.from(cls);
    }

    /**
     * 강의 목록. {@code status}가 주어지면 해당 상태만 필터.
     * 응답은 {@link PagedResponse}로 감싸 Spring {@code Page} 직렬화의 변동 가능성을 차단한다.
     */
    @GetMapping
    public PagedResponse<CourseClassResponse> list(
            @RequestParam(required = false) ClassStatus status,
            @Valid @ModelAttribute PageQuery query
    ) {
        Page<CourseClass> page = courseClassService.list(status, query.toPageable());
        return PagedResponse.of(page, CourseClassResponse::from);
    }

    /** 강의 상세. 존재하지 않으면 404 {@code NOT_FOUND}. */
    @GetMapping("/{id}")
    public CourseClassResponse detail(@PathVariable("id") long classId) {
        return CourseClassResponse.from(courseClassService.detail(classId));
    }
}
