package dev.moonservice.springpreview;

import com.fasterxml.jackson.databind.JsonNode;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import dev.moonservice.scoringprototype.UsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
class PreviewController {
    private final PreviewEvaluator previewEvaluator = new PreviewEvaluator();

    @PostMapping("/api/preview")
    ResponseEntity<String> preview(@RequestBody JsonNode request) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(previewEvaluator.evaluateJson(request));
    }
}

@ControllerAdvice
class PreviewErrorHandler {
    @ExceptionHandler(UsageException.class)
    ResponseEntity<Map<String, Object>> invalidRequest(UsageException ex) {
        return invalidRequest(ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> malformedJson() {
        return invalidRequest("Request body must be valid JSON.");
    }

    private static ResponseEntity<Map<String, Object>> invalidRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status", "invalid_request",
                        "generatedAt", Instant.now().toString(),
                        "message", message
                ));
    }
}
