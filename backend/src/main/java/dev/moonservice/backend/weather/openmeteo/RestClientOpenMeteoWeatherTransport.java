package dev.moonservice.backend.weather.openmeteo;

import dev.moonservice.backend.openmeteo.OpenMeteoRestTransport;
import dev.moonservice.backend.openmeteo.OpenMeteoRestTransportException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

final class RestClientOpenMeteoWeatherTransport implements OpenMeteoWeatherTransport {
    private final OpenMeteoRestTransport delegate;

    RestClientOpenMeteoWeatherTransport(RestClient.Builder restClientBuilder) {
        this.delegate = new OpenMeteoRestTransport(restClientBuilder);
    }

    RestClientOpenMeteoWeatherTransport(RestClient.Builder restClientBuilder, Duration timeout) {
        this.delegate = new OpenMeteoRestTransport(restClientBuilder, timeout);
    }

    @Override
    public String get(URI requestUri, Duration timeout) throws OpenMeteoWeatherTransportException {
        try {
            return delegate.get(requestUri);
        } catch (OpenMeteoRestTransportException ex) {
            throw OpenMeteoWeatherTransportException.fromRestTransport(ex);
        }
    }
}
