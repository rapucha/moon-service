package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
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
    ResponseEntity<ErrorResponse> invalidRequest(InvalidOpportunitySearchRequestException ex) {
        return invalidRequest(ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformedJson() {
        return invalidRequest("Request body must be valid JSON.");
    }

    private static ResponseEntity<ErrorResponse> invalidRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(
                        "invalid_request",
                        Instant.now().toString(),
                        message));
    }
}
