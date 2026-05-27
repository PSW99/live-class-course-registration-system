package com.liveclass.registration.controller.dto;

import com.liveclass.registration.domain.WaitlistEntry;
import java.time.OffsetDateTime;

/**
 * 대기열 응답. 엔티티를 직접 노출하지 않고 식별자/순번/타임스탬프만 전달.
 *
 * {@code position}은 1-base 순번이며 응답 시점에 카운트로 산출된다.
 * {@code classId}·{@code userId}는 LAZY proxy의 id만 접근해 추가 SELECT를 발생시키지 않는다.
 */
public record WaitlistResponse(
        Long waitlistId,
        Long classId,
        Long userId,
        Integer position,
        OffsetDateTime createdAt
) {

    public static WaitlistResponse from(WaitlistEntry entry, int position) {
        return new WaitlistResponse(
                entry.getId(),
                entry.getCourseClass().getId(),
                entry.getUser().getId(),
                position,
                entry.getCreatedAt()
        );
    }
}
