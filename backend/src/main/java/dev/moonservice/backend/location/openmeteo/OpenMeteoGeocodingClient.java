package dev.moonservice.backend.location.openmeteo;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

public class OpenMeteoGeocodingClient implements LocationResolver {
    static final URI DEFAULT_ENDPOINT = URI.create("https://geocoding-api.open-meteo.com/v1/search");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_TRANSPORT_RETRIES = 1;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(1);
    private static final int DEFAULT_RESULT_COUNT = 10;
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String BACKEND_LOCATION_ID_PREFIX = LocationProvider.OPEN_METEO.id() + "-";

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
                new RetryingOpenMeteoGeocodingTransport(
                        new RestClientOpenMeteoGeocodingTransport(RestClient.builder(), DEFAULT_TIMEOUT),
                        MAX_TRANSPORT_RETRIES,
                        MAX_RETRY_AFTER),
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
        } catch (OpenMeteoGeocodingTransportException ex) {
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
        OptionalDouble latitude = finiteDoubleField(result, "latitude");
        OptionalDouble longitude = finiteDoubleField(result, "longitude");
        OptionalDouble elevation = finiteDoubleField(result, "elevation");

        if (providerId.isBlank()
                || name.isBlank()
                || timezone.isBlank()
                || countryCode.isBlank()
                || latitude.isEmpty()
                || longitude.isEmpty()
                || elevation.isEmpty()) {
            return java.util.Optional.empty();
        }

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (ZoneRulesException ex) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new ResolvedLocation(
                backendLocationId(providerId),
                new ProviderLocationId(LocationProvider.OPEN_METEO, providerId),
                displayName(result, name, countryCode),
                latitude.getAsDouble(),
                longitude.getAsDouble(),
                (int) Math.round(elevation.getAsDouble()),
                zoneId,
                countryCode));
    }

    private static String backendLocationId(String providerId) {
        return BACKEND_LOCATION_ID_PREFIX + providerId;
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

    private static OptionalDouble finiteDoubleField(JsonNode node, String fieldName) {
        String rawValue = stringFieldOrBlank(node, fieldName);
        if (rawValue.isBlank()) {
            return OptionalDouble.empty();
        }

        double value;
        try {
            value = Double.parseDouble(rawValue);
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
        if (!Double.isFinite(value)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }
}
