package com.liveclass.registration.controller;

import com.liveclass.registration.controller.dto.WaitlistResponse;
import com.liveclass.registration.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기열 엔드포인트.
 *
 * 등록·이탈·조회 모두 본인 행위만 가능하므로 컨트롤러는 얇게 유지하고 모든 검증은 서비스 레이어에서 수행한다.
 * 분산락이 없으므로 LockFacade 경유 없이 {@link WaitlistService}를 직접 호출한다.
 */
@RestController
@RequestMapping("/api/classes/{classId}/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WaitlistResponse join(
            @PathVariable("classId") long classId,
            @RequestHeader("X-User-Id") long requesterId
    ) {
        return waitlistService.join(classId, requesterId);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(
            @PathVariable("classId") long classId,
            @RequestHeader("X-User-Id") long requesterId
    ) {
        waitlistService.leave(classId, requesterId);
    }

    @GetMapping("/me")
    public WaitlistResponse findMine(
            @PathVariable("classId") long classId,
            @RequestHeader("X-User-Id") long requesterId
    ) {
        return waitlistService.findMine(classId, requesterId);
    }
}
