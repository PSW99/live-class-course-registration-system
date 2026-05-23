package com.liveclass.registration.controller;

import com.liveclass.registration.controller.dto.CourseClassResponse;
import com.liveclass.registration.controller.dto.CreateCourseClassRequest;
import com.liveclass.registration.controller.dto.UpdateClassStatusRequest;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.service.CourseClassService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 강의 등록과 상태 전이 엔드포인트. */
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
}
