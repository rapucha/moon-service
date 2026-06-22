package dev.moonservice.backend.location.openmeteo;

import java.net.URI;
import java.time.Duration;

/**
 * Single-method seam so tests can supply fake provider transports with lambdas.
 */
@FunctionalInterface
interface OpenMeteoGeocodingTransport {
    String get(URI requestUri, Duration timeout) throws OpenMeteoGeocodingTransportException;
}
