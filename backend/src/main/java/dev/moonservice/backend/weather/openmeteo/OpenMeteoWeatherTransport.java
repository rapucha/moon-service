package dev.moonservice.backend.weather.openmeteo;

import java.net.URI;
import java.time.Duration;

interface OpenMeteoWeatherTransport {
    String get(URI requestUri, Duration timeout) throws OpenMeteoWeatherTransportException;
}
