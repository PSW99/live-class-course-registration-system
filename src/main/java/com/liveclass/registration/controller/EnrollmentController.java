package com.liveclass.registration.controller;

import com.liveclass.registration.controller.dto.CreateEnrollmentRequest;
import com.liveclass.registration.controller.dto.EnrollmentResponse;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.service.EnrollmentLockFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수강 신청 엔드포인트.
 *
 * 컨트롤러는 {@code EnrollmentService}가 아니라 {@code EnrollmentLockFacade}를 주입받는다 —
 * 분산락을 거치지 않는 직접 호출 경로를 만들지 않기 위함이다.
 */
@RestController
@RequestMapping("/api/enrollments")
@RequiredArgsConstructor
public class EnrollmentController {

    private final EnrollmentLockFacade enrollmentLockFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse create(
            @RequestHeader("X-User-Id") long requesterId,
            @Valid @RequestBody CreateEnrollmentRequest request
    ) {
        Enrollment enrollment = enrollmentLockFacade.enroll(requesterId, request.classId());
        return EnrollmentResponse.from(enrollment);
    }
}
