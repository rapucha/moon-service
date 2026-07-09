package dev.moonservice.backend.web;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {
    private final ApplicationAvailability applicationAvailability;
    private final BuildRevision buildRevision;

    HealthController(ApplicationAvailability applicationAvailability, BuildRevision buildRevision) {
        this.applicationAvailability = applicationAvailability;
        this.buildRevision = buildRevision;
    }

    @GetMapping("/healthz")
    ResponseEntity<HealthStatusResponse> liveness() {
        return status(applicationAvailability.getLivenessState() == LivenessState.CORRECT);
    }

    @GetMapping("/readyz")
    ResponseEntity<HealthStatusResponse> readiness() {
        return status(applicationAvailability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC);
    }

    private ResponseEntity<HealthStatusResponse> status(boolean available) {
        return ResponseEntity
                .status(available ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new HealthStatusResponse(available ? "ok" : "unavailable", buildRevision.value()));
    }

    record HealthStatusResponse(String status, String revision) {
    }
}
