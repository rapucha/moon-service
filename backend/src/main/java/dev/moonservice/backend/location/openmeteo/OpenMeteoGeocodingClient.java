package dev.moonservice.backend.location.openmeteo;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.ResolvedLocation;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.List;

public class OpenMeteoGeocodingClient implements LocationResolver {
    static final URI DEFAULT_ENDPOINT = URI.create("https://geocoding-api.open-meteo.com/v1/search");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_RESULT_COUNT = 10;
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String PROVIDER_ID_PREFIX = "openmeteo:";

    private final URI endpoint;
    private final String language;
    private final int resultCount;
    private final Duration timeout;
    private final OpenMeteoGeocodingTransport transport;
    private final ObjectMapper objectMapper;

    public OpenMeteoGeocodingClient() {
        this(
                DEFAULT_ENDPOINT,
                DEFAULT_LANGUAGE,
                DEFAULT_RESULT_COUNT,
                DEFAULT_TIMEOUT,
                new JavaHttpOpenMeteoGeocodingTransport(HttpClient.newHttpClient()),
                new ObjectMapper());
    }

    OpenMeteoGeocodingClient(OpenMeteoGeocodingTransport transport) {
        this(DEFAULT_ENDPOINT, DEFAULT_LANGUAGE, DEFAULT_RESULT_COUNT, DEFAULT_TIMEOUT, transport, new ObjectMapper());
    }

    OpenMeteoGeocodingClient(
            URI endpoint,
            String language,
            int resultCount,
            Duration timeout,
            OpenMeteoGeocodingTransport transport,
            ObjectMapper objectMapper
    ) {
        this.endpoint = endpoint;
        this.language = language;
        this.resultCount = resultCount;
        this.timeout = timeout;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    @Override
    public LocationResolution resolve(LocationQuery query) {
        String body;
        try {
            body = transport.get(requestUri(query), timeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return LocationResolution.temporarilyUnavailable();
        } catch (IOException ex) {
            return LocationResolution.temporarilyUnavailable();
        }

        if (body == null || body.isBlank()) {
            return LocationResolution.temporarilyUnavailable();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException ex) {
            return LocationResolution.temporarilyUnavailable();
        }

        return toResolution(root);
    }

    URI requestUri(LocationQuery query) {
        return UriComponentsBuilder.fromUri(endpoint)
                .queryParam("name", query.text())
                .queryParam("count", resultCount)
                .queryParam("language", language)
                .queryParam("format", "json")
                .build()
                .encode()
                .toUri();
    }

    private static LocationResolution toResolution(JsonNode root) {
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return LocationResolution.temporarilyUnavailable();
        }
        if (results.isEmpty()) {
            return LocationResolution.notFound();
        }

        List<ResolvedLocation> candidates = new ArrayList<>();
        for (JsonNode result : results) {
            toLocation(result).ifPresent(candidates::add);
        }

        if (candidates.isEmpty()) {
            return LocationResolution.temporarilyUnavailable();
        }
        if (candidates.size() == 1) {
            return LocationResolution.resolved(candidates.getFirst());
        }
        return LocationResolution.ambiguous(candidates);
    }

    private static java.util.Optional<ResolvedLocation> toLocation(JsonNode result) {
        String providerId = stringFieldOrBlank(result, "id");
        String name = stringFieldOrBlank(result, "name");
        String timezone = stringFieldOrBlank(result, "timezone");
        String countryCode = stringFieldOrBlank(result, "country_code");

        if (providerId.isBlank() || name.isBlank() || timezone.isBlank() || countryCode.isBlank()) {
            return java.util.Optional.empty();
        }

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (ZoneRulesException ex) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new ResolvedLocation(
                PROVIDER_ID_PREFIX + providerId,
                displayName(result, name, countryCode),
                zoneId,
                countryCode));
    }

    private static String displayName(JsonNode result, String name, String countryCode) {
        List<String> parts = new ArrayList<>();
        addDisplayPart(parts, name);
        addDisplayPart(parts, stringFieldOrBlank(result, "admin1"));
        String country = stringFieldOrBlank(result, "country");
        addDisplayPart(parts, country.isBlank() ? countryCode : country);
        return String.join(", ", parts);
    }

    private static void addDisplayPart(List<String> parts, String value) {
        if (!value.isBlank() && !parts.contains(value)) {
            parts.add(value);
        }
    }

    private static String stringFieldOrBlank(JsonNode node, String fieldName) {
        return node.path(fieldName).asString().strip();
    }

    private record JavaHttpOpenMeteoGeocodingTransport(HttpClient httpClient) implements OpenMeteoGeocodingTransport {
        @Override
        public String get(URI requestUri, Duration timeout) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(requestUri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("User-Agent", "moon-service-backend/0.1")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Open-Meteo Geocoding returned HTTP " + response.statusCode());
            }
            return response.body();
        }
    }
}

@FunctionalInterface
interface OpenMeteoGeocodingTransport {
    String get(URI requestUri, Duration timeout) throws IOException, InterruptedException;
}
