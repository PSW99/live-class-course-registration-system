package com.liveclass.registration.global;

import com.liveclass.registration.global.exception.DomainException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 모든 컨트롤러의 예외를 표준 ErrorResponse로 변환하는 단일 진입점.
 *
 * 새 도메인 예외가 추가되면 DomainException을 상속하기만 하면
 * handleDomain이 자동으로 처리한다 — 본 클래스 수정 불필요.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomain(DomainException ex) {
        log.warn("domain exception: code={}, message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", message));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        String message = "필수 헤더 누락: " + ex.getHeaderName();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("MISSING_HEADER", message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "잘못된 값 형식: " + ex.getName();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_HEADER", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex) {
        // 메시지에 stacktrace 노출을 피하기 위해 고정 문구 사용
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("MALFORMED_JSON", "요청 본문을 해석할 수 없습니다"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(500)
                .body(new ErrorResponse("INTERNAL_ERROR", "예기치 못한 오류가 발생했습니다"));
    }
}
