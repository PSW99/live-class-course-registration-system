package com.liveclass.registration.controller.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 페이지 응답 wrapper. Spring {@code Page<T>}를 직접 직렬화하지 않고 이 DTO로 감싸 응답한다.
 *
 * 노출 필드는 {@code content, page, size, totalElements, totalPages} 다섯 개로 고정 — Spring/Jackson
 * 버전 변경에 의한 직렬화 스키마 변동 가능성을 차단한다.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    /** {@link Page} 엔티티 목록을 {@code mapper}로 변환해 PagedResponse로 감싼다. */
    public static <E, R> PagedResponse<R> of(Page<E> page, Function<E, R> mapper) {
        return new PagedResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
