package com.liveclass.registration.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 페이지 쿼리 파라미터 DTO. 컨트롤러에서 {@code @Valid @ModelAttribute}로 받아 size 상한 검증과
 * 기본값 적용을 일괄 처리한다.
 *
 * 검증 실패는 {@code MethodArgumentNotValidException}으로 매핑되어
 * {@code GlobalExceptionHandler.handleValidation}이 400 {@code VALIDATION_FAILED}로 응답한다.
 * {@code @RequestParam}에 직접 {@code @Max}를 붙이면 {@code ConstraintViolationException}이 던져져
 * 기존 핸들러가 잡지 못하므로 반드시 이 DTO를 경유한다.
 */
public record PageQuery(
        @Min(value = 0, message = "0 이상이어야 합니다")
        Integer page,

        @Min(value = 1, message = "1 이상이어야 합니다")
        @Max(value = 100, message = "100 이하여야 합니다")
        Integer size
) {

    /** 누락된 파라미터는 기본값(page=0, size=20)으로 채운다. */
    public PageQuery {
        if (page == null) page = 0;
        if (size == null) size = 20;
    }

    public Pageable toPageable() {
        return PageRequest.of(page, size);
    }

    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}
