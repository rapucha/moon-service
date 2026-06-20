package dev.moonservice.backend.web;

import dev.moonservice.scoringprototype.PreviewEvaluator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class PreviewController {
    private final PreviewEvaluator previewEvaluator;

    PreviewController(PreviewEvaluator previewEvaluator) {
        this.previewEvaluator = previewEvaluator;
    }

    @PostMapping("/api/preview")
    ResponseEntity<String> preview(@RequestBody JsonNode request) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(previewEvaluator.evaluateJson(request));
    }
}
