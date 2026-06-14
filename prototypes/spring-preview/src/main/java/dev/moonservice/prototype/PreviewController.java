package dev.moonservice.prototype;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    @PostMapping("/api/preview")
    PreviewResponse preview(@RequestBody JsonNode request) {
        PrototypeConfig config = RequestConfigReader.fromJson(request);
        PrototypeResult result = new OpportunityService().evaluate(config);
        return new PreviewResponseFactory().from(result);
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
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", "invalid_request",
                "generatedAt", Instant.now().toString(),
                "message", message
        ));
    }
}
