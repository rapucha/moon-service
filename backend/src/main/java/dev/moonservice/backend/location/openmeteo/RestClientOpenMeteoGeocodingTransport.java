package dev.moonservice.backend.location.openmeteo;

import dev.moonservice.backend.openmeteo.OpenMeteoRestTransport;
import dev.moonservice.backend.openmeteo.OpenMeteoRestTransportException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

final class RestClientOpenMeteoGeocodingTransport implements OpenMeteoGeocodingTransport {
    private final OpenMeteoRestTransport delegate;

    RestClientOpenMeteoGeocodingTransport(RestClient.Builder restClientBuilder) {
        this.delegate = new OpenMeteoRestTransport(restClientBuilder);
    }

    RestClientOpenMeteoGeocodingTransport(RestClient.Builder restClientBuilder, Duration timeout) {
        this.delegate = new OpenMeteoRestTransport(restClientBuilder, timeout);
    }

    @Override
    public String get(URI requestUri, Duration timeout) throws OpenMeteoGeocodingTransportException {
        try {
            return delegate.get(requestUri);
        } catch (OpenMeteoRestTransportException ex) {
            throw OpenMeteoGeocodingTransportException.fromRestTransport(ex);
        }
    }
}
