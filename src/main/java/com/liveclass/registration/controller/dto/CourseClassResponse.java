package com.liveclass.registration.controller.dto;

import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 강의 응답 페이로드. creator는 식별자만 노출해 이메일 등 노출을 방지. */
public record CourseClassResponse(
        Long id,
        Long creatorId,
        String title,
        String description,
        BigDecimal price,
        Integer capacity,
        Integer currentCount,
        LocalDate startDate,
        LocalDate endDate,
        ClassStatus status,
        OffsetDateTime createdAt
) {

    public static CourseClassResponse from(CourseClass cls) {
        return new CourseClassResponse(
                cls.getId(),
                // LAZY proxy의 getId()는 추가 쿼리 없이 식별자를 반환한다
                cls.getCreator().getId(),
                cls.getTitle(),
                cls.getDescription(),
                cls.getPrice(),
                cls.getCapacity(),
                cls.getCurrentCount(),
                cls.getStartDate(),
                cls.getEndDate(),
                cls.getStatus(),
                cls.getCreatedAt()
        );
    }
}
