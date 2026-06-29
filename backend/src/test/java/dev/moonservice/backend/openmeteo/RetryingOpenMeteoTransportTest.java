package dev.moonservice.backend.openmeteo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

class RetryingOpenMeteoTransportTest {
    private static final URI REQUEST_URI = URI.create("https://example.test/open-meteo");

    @Test
    void maxRetriesZeroDoesNotRetryRetryableFailures() {
        ScriptedTransport delegate = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoTransportException.transientHttp(503, Optional.empty())),
                ResponseStep.success("{}"));
        RetryingOpenMeteoTransport transport = new RetryingOpenMeteoTransport(
                delegate,
                0,
                Duration.ofSeconds(1));

        assertThrows(OpenMeteoTransportException.class, () -> transport.get(REQUEST_URI));

        assertEquals(1, delegate.calls());
    }

    @Test
    void maxRetriesAllowsConfiguredRetryCount() {
        ScriptedTransport delegate = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoTransportException.transientHttp(503, Optional.empty())),
                ResponseStep.failure(OpenMeteoTransportException.ioFailure(null)),
                ResponseStep.success("{}"));
        RetryingOpenMeteoTransport transport = new RetryingOpenMeteoTransport(
                delegate,
                2,
                Duration.ofSeconds(1));

        assertEquals("{}", transport.get(REQUEST_URI));
        assertEquals(3, delegate.calls());
    }

    private record ResponseStep(String body, OpenMeteoTransportException failure) {
        static ResponseStep success(String body) {
            return new ResponseStep(body, null);
        }

        static ResponseStep failure(OpenMeteoTransportException failure) {
            return new ResponseStep(null, failure);
        }
    }

    private static final class ScriptedTransport implements OpenMeteoTransport {
        private final List<ResponseStep> steps;
        private int calls;

        private ScriptedTransport(ResponseStep... steps) {
            this.steps = Arrays.asList(steps);
        }

        @Override
        public String get(URI requestUri) throws OpenMeteoTransportException {
            if (calls >= steps.size()) {
                throw new AssertionError("Unexpected Open-Meteo transport call.");
            }
            ResponseStep step = steps.get(calls);
            calls++;
            if (step.failure() != null) {
                throw step.failure();
            }
            return step.body();
        }

        int calls() {
            return calls;
        }
    }
}
