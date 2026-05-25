package com.liveclass.registration.controller;

import com.liveclass.registration.controller.dto.CreateEnrollmentRequest;
import com.liveclass.registration.controller.dto.EnrollmentResponse;
import com.liveclass.registration.controller.dto.PageQuery;
import com.liveclass.registration.controller.dto.PagedResponse;
import com.liveclass.registration.domain.Enrollment;
import com.liveclass.registration.service.EnrollmentLockFacade;
import com.liveclass.registration.service.EnrollmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수강 신청·확정 엔드포인트.
 *
 * 신청은 cross-row 자원(강의 정원)을 보호해야 하므로 {@code EnrollmentLockFacade}(분산락 + 비관적 락)를 경유한다.
 * 확정은 단일 enrollment row만 수정해 보호할 cross-row 자원이 없으므로 {@code EnrollmentService}를 직접 호출한다.
 *
 * 두 경로가 한 컨트롤러에 공존하는 이유: 보호 대상이 다른 작업에 동일한 락 비용을 부과하지 않기 위함.
 */
@RestController
@RequestMapping("/api/enrollments")
@RequiredArgsConstructor
public class EnrollmentController {

    private final EnrollmentLockFacade enrollmentLockFacade;
    private final EnrollmentService enrollmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse create(
            @RequestHeader("X-User-Id") long requesterId,
            @Valid @RequestBody CreateEnrollmentRequest request
    ) {
        Enrollment enrollment = enrollmentLockFacade.enroll(requesterId, request.classId());
        return EnrollmentResponse.from(enrollment);
    }

    /**
     * 결제 확정. PENDING 상태의 enrollment를 CONFIRMED로 전이한다.
     *
     * 단일 row 변경이라 {@code EnrollmentService}를 직접 호출한다.
     * 신청처럼 cross-row 자원을 보호해야 하는 경우에만 {@code EnrollmentLockFacade}를 경유한다.
     */
    @PostMapping("/{id}/confirm")
    public EnrollmentResponse confirm(
            @PathVariable("id") long enrollmentId,
            @RequestHeader("X-User-Id") long requesterId
    ) {
        Enrollment enrollment = enrollmentService.confirm(enrollmentId, requesterId);
        return EnrollmentResponse.from(enrollment);
    }

    /**
     * 수강 취소. PENDING/CONFIRMED enrollment를 CANCELLED로 전이하고 정원을 즉시 반환한다.
     *
     * 강의 row의 비관적 락은 서비스가 잡지만 cross-row 분산락은 불필요 —
     * 본인 본인 enrollment 취소는 enroll만큼의 high-contention이 발생하지 않는다.
     */
    @PostMapping("/{id}/cancel")
    public EnrollmentResponse cancel(
            @PathVariable("id") long enrollmentId,
            @RequestHeader("X-User-Id") long requesterId
    ) {
        Enrollment enrollment = enrollmentService.cancel(enrollmentId, requesterId);
        return EnrollmentResponse.from(enrollment);
    }

    /**
     * 본인 신청 목록. {@code X-User-Id}가 가리키는 사용자의 enrollment를 {@code created_at DESC}로 페이지 반환.
     *
     * 사용자 존재 검증은 수행하지 않으며 존재하지 않으면 빈 페이지로 응답한다.
     */
    @GetMapping("/me")
    public PagedResponse<EnrollmentResponse> listMine(
            @RequestHeader("X-User-Id") long requesterId,
            @Valid @ModelAttribute PageQuery query
    ) {
        Page<Enrollment> page = enrollmentService.listByUser(requesterId, query.toPageable());
        return PagedResponse.of(page, EnrollmentResponse::from);
    }
}
