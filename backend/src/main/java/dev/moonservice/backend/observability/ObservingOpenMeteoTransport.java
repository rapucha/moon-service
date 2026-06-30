package dev.moonservice.backend.observability;

import dev.moonservice.backend.openmeteo.OpenMeteoTransport;
import dev.moonservice.backend.openmeteo.OpenMeteoTransportException;

import java.net.URI;
import java.util.Objects;

public final class ObservingOpenMeteoTransport implements OpenMeteoTransport {
    private final OpenMeteoTransport delegate;
    private final OpenMeteoObservability.ProviderMetrics metrics;

    public ObservingOpenMeteoTransport(
            OpenMeteoTransport delegate,
            OpenMeteoObservability.ProviderMetrics metrics
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public String get(URI requestUri) throws OpenMeteoTransportException {
        metrics.recordProviderCall();
        try {
            return delegate.get(requestUri);
        } catch (OpenMeteoTransportException ex) {
            metrics.recordTransportFailure(ex.kind());
            throw ex;
        }
    }
}
