package dev.moonservice.backend.openmeteo;

import java.net.URI;

@FunctionalInterface
public interface OpenMeteoTransport {
    String get(URI requestUri) throws OpenMeteoTransportException;
}
