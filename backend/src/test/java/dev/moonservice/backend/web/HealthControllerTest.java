package dev.moonservice.backend.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HealthControllerTest {
    private final ApplicationAvailabilityBean availability = new ApplicationAvailabilityBean();
    private final HealthController controller = new HealthController(availability, new BuildRevision("test-revision"));

    @Test
    void failsClosedBeforeApplicationAvailabilityEvents() {
        assertUnavailable(controller.liveness());
        assertUnavailable(controller.readiness());
    }

    @Test
    void mapsLiveAndReadyStatesToSuccessfulResponses() {
        publish(LivenessState.CORRECT);
        publish(ReadinessState.ACCEPTING_TRAFFIC);

        assertAvailable(controller.liveness());
        assertAvailable(controller.readiness());
    }

    @Test
    void mapsBrokenAndRefusingStatesToUnavailableResponses() {
        publish(LivenessState.CORRECT);
        publish(ReadinessState.ACCEPTING_TRAFFIC);
        publish(LivenessState.BROKEN);
        publish(ReadinessState.REFUSING_TRAFFIC);

        assertUnavailable(controller.liveness());
        assertUnavailable(controller.readiness());
    }

    @Test
    void normalizesLocalBuildRevisionAndRejectsUnsafeValues() {
        assertEquals(BuildRevision.LOCAL_REVISION, new BuildRevision("  ").value());
        assertEquals("abc123-dirty", new BuildRevision("  abc123-dirty  ").value());
        assertThrows(IllegalArgumentException.class, () -> new BuildRevision("revision with spaces"));
    }

    private void publish(AvailabilityState state) {
        availability.onApplicationEvent(new AvailabilityChangeEvent<>(this, state));
    }

    private static void assertAvailable(ResponseEntity<HealthController.HealthStatusResponse> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("ok", response.getBody().status());
        assertEquals("test-revision", response.getBody().revision());
    }

    private static void assertUnavailable(ResponseEntity<HealthController.HealthStatusResponse> response) {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("unavailable", response.getBody().status());
        assertEquals("test-revision", response.getBody().revision());
    }
}
