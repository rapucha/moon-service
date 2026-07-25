package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;

@ControllerAdvice
class OpportunitySearchErrorHandler {
    @ExceptionHandler(InvalidOpportunitySearchRequestException.class)
    ResponseEntity<ErrorResponse> invalidRequest(
            InvalidOpportunitySearchRequestException ex,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformedJson(HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request body must be valid JSON.",
                request);
    }

    @ExceptionHandler(OpportunityRequestTooLargeException.class)
    ResponseEntity<ErrorResponse> requestTooLarge(HttpServletRequest request) {
        return error(
                HttpStatus.CONTENT_TOO_LARGE,
                "request_too_large",
                "Request body exceeds 16,384 bytes.",
                request);
    }

    @ExceptionHandler(UnsupportedOpportunityMediaTypeException.class)
    ResponseEntity<ErrorResponse> unsupportedMediaType(HttpServletRequest request) {
        return error(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_media_type",
                "Content-Type must be application/json.",
                request);
    }

    private static ResponseEntity<ErrorResponse> error(
            HttpStatus httpStatus,
            String status,
            String message,
            HttpServletRequest request
    ) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(httpStatus)
                .contentType(MediaType.APPLICATION_JSON);
        if (isProductPost(request)) {
            response.header(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        return response
                .body(new ErrorResponse(
                        status,
                        Instant.now().toString(),
                        message));
    }

    private static boolean isProductPost(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && "/api/opportunities".equals(HostedAlphaSurfaceFilter.applicationPath(request));
    }
}

final class OpportunityRequestTooLargeException extends RuntimeException {
}

final class UnsupportedOpportunityMediaTypeException extends RuntimeException {
}
