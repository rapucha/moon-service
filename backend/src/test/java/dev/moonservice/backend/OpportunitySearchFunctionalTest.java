package dev.moonservice.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.backend.observability.RequestLoggingFilter;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import dev.moonservice.backend.weather.TestWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.admin.token=test-admin-token",
                "moon.hosted-alpha.enabled=false",
                "moon.build.revision=test-revision"
        })
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
@Tag("functional")
class OpportunitySearchFunctionalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OpenMeteoObservability openMeteoObservability;

    @Autowired
    private LocationResolver locationResolver;

    @Autowired
    private WeatherForecastProvider weatherForecastProvider;

    @TestConfiguration
    static class TestOpenMeteoLocationResolverConfiguration {
        @Bean
        @Primary
        LocationResolver testOpenMeteoLocationResolver() {
            return spy(new TestOpenMeteoLocationResolver());
        }

        @Bean
        @Primary
        WeatherForecastProvider testWeatherForecastProvider() {
            return spy(new TestWeatherForecastProvider());
        }

        @Bean
        @Primary
        Clock fixedOpportunityClock() {
            return Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Praha", "Prague", "prague-cz"})
    void returnsOpportunitySearchResponseForTestOpenMeteoQuery(String query) {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("q", query)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Content-Security-Policy")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.location.id").isEqualTo("prague-cz")
                .jsonPath("$.forecastHorizonDays").isEqualTo(7)
                .jsonPath("$.startsAt").exists()
                .jsonPath("$.maxMoonAltitudeDegrees").isEqualTo(90.0)
                .jsonPath("$.opportunities[0].suggestedAt").exists()
                .jsonPath("$.opportunities[0].moon.brightLimbTiltDegrees").isNumber()
                .jsonPath("$.opportunities[0].moon.northPoleTiltDegrees").isNumber()
                .jsonPath("$.opportunities[0].moonPass.path.samples[0].moonPhaseAngleDegrees").isNumber()
                .jsonPath("$.opportunities[0].moonPass.path.samples[0].brightLimbTiltDegrees").isNumber()
                .jsonPath("$.opportunities[0].moonPass.path.samples[0].northPoleTiltDegrees").isNumber();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/search?q=Praha"})
    void servesBrowserLookupPage(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(body -> {
                    assertTrue(body.contains("id=\"search-form\""));
                    assertTrue(body.contains("href=\"/favicon.svg\""));
                    assertTrue(body.contains("Moon windows near a city"));
                    assertTrue(body.contains("href=\"/about\""));
                    assertTrue(body.contains("id=\"recent-searches\""));
                    assertFalse(body.contains("Privacy and caveats"));
                    assertFalse(body.contains("Data sources and alpha use"));
                    assertFalse(body.contains("This is a noncommercial tester alpha."));
                    assertTrue(body.contains("type=\"module\" src=\"/app.js\""));
                });
    }

    @Test
    void servesAboutPage() {
        webTestClient.get()
                .uri("/about")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(body -> {
                    assertTrue(body.contains("About Moon Service"));
                    assertTrue(body.contains("Why It Exists"));
                    assertTrue(body.contains("Search opportunities"));
                    assertTrue(body.contains("Privacy and Provider Processing"));
                    assertTrue(body.contains("Data Sources and Service Limits"));
                    assertTrue(body.contains("https://www.geonames.org/export/"));
                    assertTrue(body.contains("backend request logs containing coordinates for up to 90 days"));
                    assertTrue(body.contains("Moon Service adds no visitor tracking."));
                    assertFalse(body.contains("This is a noncommercial tester alpha."));
                    assertTrue(body.contains("NASA's Scientific Visualization Studio"));
                });
    }

    @Test
    void exposesProviderIndependentOperationalHealth() {
        long geocodingCalls = openMeteoObservability.geocodingSnapshot().calls();
        long weatherCalls = openMeteoObservability.weatherSnapshot().calls();

        for (String path : new String[]{"/healthz", "/readyz"}) {
            webTestClient.get()
                    .uri(path)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control", "no-store")
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("ok")
                    .jsonPath("$.revision").isEqualTo("test-revision");
        }

        assertEquals(geocodingCalls, openMeteoObservability.geocodingSnapshot().calls());
        assertEquals(weatherCalls, openMeteoObservability.weatherSnapshot().calls());
    }

    @Test
    void servesBrowserLookupAssets() {
        webTestClient.get()
                .uri("/app.js")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/javascript")
                .expectBody(String.class)
                .value(body -> {
                    assertTrue(body.contains("from \"./api.js\""));
                    assertTrue(body.contains("from \"./responseView.js\""));
                });

        webTestClient.get()
                .uri("/api.js")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/javascript")
                .expectBody(String.class)
                .value(body -> {
                    assertTrue(body.contains("/api/opportunities?q="));
                    assertTrue(body.contains("/api/opportunities?locationId="));
                });

        webTestClient.get()
                .uri("/recentSearches.js")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/javascript")
                .expectBody(String.class)
                .value(body -> assertTrue(body.contains("moonService.recentSearches.v1")));

        webTestClient.get()
                .uri("/styles.css")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/css")
                .expectBody(String.class)
                .value(body -> assertTrue(body.contains(".opportunity-card")));

        webTestClient.get()
                .uri("/favicon.svg")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("image/svg+xml")
                .expectBody(String.class)
                .value(body -> {
                    assertTrue(body.contains("aria-label=\"Moon Service\""));
                    assertTrue(body.contains("viewBox=\"0 0 64 64\""));
                });
    }

    @Test
    void rejectsAdminStatusWithoutAdminToken() {
        webTestClient.get()
                .uri("/admin/status")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(RequestLoggingFilter.REQUEST_ID_HEADER);
    }

    @Test
    void returnsAdminStatus() {
        webTestClient.get()
                .uri("/admin/status")
                .header("X-Moon-Admin-Token", "test-admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.app.status").isEqualTo("ok")
                .jsonPath("$.app.revision").isEqualTo("test-revision")
                .jsonPath("$.providers.openMeteoGeocoding.calls").isNumber()
                .jsonPath("$.providers.openMeteoWeather.calls").isNumber()
                .jsonPath("$.providers.operations['open-meteo-geocoding'].provider").isEqualTo("open-meteo")
                .jsonPath("$.providers.operations['open-meteo-geocoding'].operation").isEqualTo("geocoding")
                .jsonPath("$.providers.operations['open-meteo-geocoding'].usage.hourly.used").isNumber()
                .jsonPath("$.providers.operations['open-meteo-geocoding'].usage.hourly.knownLimit").isBoolean()
                .jsonPath("$.providers.operations['open-meteo-geocoding'].usage.hourly.warningState")
                .value(String.class, value -> assertTrue(!value.isBlank()))
                .jsonPath("$.providers.operations['open-meteo-weather'].provider").isEqualTo("open-meteo")
                .jsonPath("$.caches").exists();
    }

    @Test
    void returnsLocationNotFoundForUnknownTestOpenMeteoQuery() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("q", "Not A Real Test City")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("location_not_found")
                .jsonPath("$.message").isEqualTo("No matching location found.")
                .jsonPath("$.opportunities").doesNotExist();
    }

    @Test
    void returnsOpportunitySearchResponseForNonFixtureResolvedLocation() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("q", "Amsterdam")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.location.id").isEqualTo("amsterdam-nl")
                .jsonPath("$.location.displayName").isEqualTo("Amsterdam, North Holland, Netherlands")
                .jsonPath("$.location.timezone").isEqualTo("Europe/Amsterdam")
                .jsonPath("$.forecastHorizonDays").isEqualTo(7)
                .jsonPath("$.opportunities[0].id").value(String.class, value ->
                        assertTrue(value.startsWith("amsterdam-nl-")))
                .jsonPath("$.opportunities[0].links.ics").value(String.class, value ->
                        assertTrue(value.startsWith("/o/amsterdam-nl-")));
    }

    @Test
    void returnsAmbiguousLocationForTestOpenMeteoProviderCandidates() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("q", "Springfield")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("ambiguous_location")
                .jsonPath("$.candidates[0].kind").isEqualTo("real_location")
                .jsonPath("$.candidates[0].id").isEqualTo("springfield-mo-us")
                .jsonPath("$.candidates[0].timezone").isEqualTo("America/Chicago")
                .jsonPath("$.candidates[1].id").isEqualTo("springfield-il-us")
                .jsonPath("$.opportunities").doesNotExist();
    }

    @Test
    void returnsOpportunitySearchResponseForSelectedLocationId() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("locationId", "springfield-mo-us")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.location.displayName").isEqualTo("Springfield, Missouri, United States")
                .jsonPath("$.location.timezone").isEqualTo("America/Chicago")
                .jsonPath("$.opportunities[0].suggestedAt").exists();
    }

    @Test
    void returnsLocationNotFoundForUnknownLocationId() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("locationId", "unknown-location")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("location_not_found")
                .jsonPath("$.message").isEqualTo("No matching location found.");
    }

    @Test
    void mapsMixedQueryAndLocationIdToInvalidRequest() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("q", "Springfield")
                        .queryParam("locationId", "springfield-mo-us")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("Use q or locationId, not both.");
    }

    @Test
    void mapsMissingQueryToInvalidRequest() {
        webTestClient.get()
                .uri("/api/opportunities")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("q is required.");
    }

    @Test
    void mapsBlankQueryToInvalidRequest() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("q", "   ")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("q must be non-empty.");
    }

    @Test
    void mapsTooLongQueryToInvalidRequest() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("q", "a".repeat(101))
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("q must be 100 characters or fewer.");
    }

    @Test
    void productPostPreservesDefaultResultsAndMarksResponsesNoStore() throws JacksonException {
        JsonNode get = responseJson(webTestClient.get()
                .uri("/api/opportunities?q=Prague").exchange().expectStatus().isOk(), false);
        JsonNode absent = productPostOk("{\"q\":\"Prague\"}");
        JsonNode empty = productPostOk("{\"q\":\"Prague\",\"preferences\":{\"version\":1}}");

        assertEquals(get.path("opportunities"), absent.path("opportunities"));
        assertEquals(get.path("opportunities"), empty.path("opportunities"));
        assertFalse(absent.has("appliedPreferenceVersion"));
        assertEquals(1, empty.path("appliedPreferenceVersion").intValue());
        assertTrue(empty.path("normalizedActiveFilters").isEmpty());
        assertEquals(0, empty.path("excludedSampleCount").intValue());
        assertFalse(empty.path("opportunities").get(0).path("moonPass").has("azimuthMatchIntervals"));
        assertEquals("location_not_found",
                productPostOk("{\"q\":\"Not A Real Test City\"}").path("status").asString());
        webTestClient.post().uri("/api/opportunities")
                .accept(MediaType.APPLICATION_XML)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"q\":\"Prague\"}").exchange()
                .expectStatus().isEqualTo(406)
                .expectHeader().valueEquals("Cache-Control", "no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"included\":{\"start\":10,\"end\":350}",
            "\"excluded\":{\"start\":350,\"end\":10}",
            "\"included\":{\"start\":10,\"end\":350},\"excluded\":{\"start\":100,\"end\":120}",
            "\"included\":{\"start\":330,\"end\":30}"
    })
    void acceptsEveryAzimuthPreferenceShape(String azimuth) throws JacksonException {
        JsonNode response = productPostOk("""
                {"q":"Prague","preferences":{"version":1,"azimuthDegrees":{%s}}}
                """.formatted(azimuth));

        assertEquals(1, response.path("appliedPreferenceVersion").intValue());
        assertTrue(response.path("normalizedActiveFilters").has("azimuthDegrees"));
    }

    @Test
    void returnsNormalizedFiltersAndPreferenceEmptyReason() throws JacksonException {
        JsonNode response = productPostOk("""
                {"locationId":"prague-cz","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":90,"maximum":90},
                  "azimuthDegrees":{"excluded":{"start":100,"end":110}},
                  "time":{"mode":"light_bucket","buckets":["night"]},
                  "namedPhases":["full_moon"],
                  "brightLimbOrientationDegrees":[{"start":0,"end":180}]}}
                """);

        JsonNode filters = response.path("normalizedActiveFilters");
        assertTrue(filters.has("altitudeDegrees") && filters.has("azimuthDegrees")
                && filters.has("time") && filters.has("namedPhases")
                && filters.has("brightLimbOrientationDegrees"));
        assertTrue(response.path("excludedSampleCount").intValue() > 0);
        assertTrue(response.path("opportunities").isEmpty());
        assertEquals("no_opportunities_match_preferences", response.at("/emptyReason/code").asString());
    }

    @Test
    void returnsCompleteAzimuthMasksForRepeatedPasses() throws JacksonException {
        JsonNode response = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "azimuthDegrees":{"included":{"start":10,"end":350}},
                  "time":{"mode":"local_clock","window":{"start":"00:00","end":"04:00"}}}}
                """);
        Map<String, JsonNode> masksByPass = new HashMap<>();
        boolean repeatedPass = false;
        boolean maskExtendsUsefulWindow = false;
        for (JsonNode opportunity : response.path("opportunities")) {
            JsonNode pass = opportunity.path("moonPass");
            JsonNode mask = pass.path("azimuthMatchIntervals");
            assertFalse(mask.isEmpty());
            JsonNode previous = masksByPass.putIfAbsent(pass.path("id").asString(), mask);
            if (previous != null) {
                repeatedPass = true;
                assertEquals(previous, mask);
            }
            Instant passStart = Instant.parse(pass.path("startsAt").asString());
            Instant passEnd = Instant.parse(pass.path("endsAt").asString());
            for (JsonNode interval : mask) {
                Instant start = Instant.parse(interval.path("startsAt").asString());
                Instant end = Instant.parse(interval.path("endsAt").asString());
                assertTrue(!start.isBefore(passStart) && !end.isAfter(passEnd) && end.isAfter(start));
                maskExtendsUsefulWindow |= start.isBefore(Instant.parse(opportunity.path("startsAt").asString()))
                        || end.isAfter(Instant.parse(opportunity.path("endsAt").asString()));
            }
        }
        JsonNode azimuthOnly = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "azimuthDegrees":{"included":{"start":10,"end":350}}}}
                """);
        assertTrue(IntStream.range(0, azimuthOnly.path("opportunities").size())
                .mapToObj(azimuthOnly.path("opportunities")::get)
                .map(opportunity -> opportunity.path("moonPass"))
                .anyMatch(pass -> pass.path("azimuthMatchIntervals").equals(
                        masksByPass.get(pass.path("id").asString()))));
        assertTrue(repeatedPass);
        assertTrue(maskExtendsUsefulWindow);
    }

    @Test
    void boundsUnknownPreferenceWarningsAndKeepsProvidersAndLogsClean(
            CapturedOutput output
    ) throws JacksonException {
        clearInvocations(locationResolver, weatherForecastProvider);
        String extras = IntStream.range(0, 21)
                .mapToObj(index -> "\"extra" + index + "\":{\"child\":\"private-marker\"}")
                .collect(Collectors.joining(","));
        JsonNode response = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":0,"maximum":90,"alt/tilde~":7},
                  "time":{"mode":"local_clock","window":{"start":"00:00","end":"23:59"},"windows":[{"win/~":"private-marker"}]},
                  "private-marker":{"child":"private-marker"},%s}}
                """.formatted(extras));

        assertEquals(24, response.path("ignoredPreferenceFieldCount").intValue());
        assertEquals(20, response.path("ignoredPreferenceFields").size());
        assertEquals(4, response.path("additionalIgnoredPreferenceFieldCount").intValue());
        assertEquals("/altitudeDegrees/alt~1tilde~0", response.at("/ignoredPreferenceFields/0").asString());
        assertEquals("/time/windows", response.at("/ignoredPreferenceFields/1").asString());
        assertEquals("/private-marker", response.at("/ignoredPreferenceFields/2").asString());
        assertTrue(output.getOut().contains(
                "ignored_preference_fields preferenceVersion=1 count=24 truncated=true"));
        assertFalse(output.getOut().contains("private-marker"));
        verify(locationResolver).resolve(new LocationQuery("Prague"));
        verify(weatherForecastProvider).forecastFor(any(), any(), any(), eq(7));
    }

    @Test
    void returnsOpportunitySearchResponseForPragueFixtureRequest() {
        webTestClient.post().uri("/api/opportunities/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "locationId": "prague-cz",
                          "start": "2026-06-29",
                          "forecastHorizonDays": 7,
                          "maxMoonAltitudeDegrees": 12,
                          "limit": 5
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.location.id").isEqualTo("openmeteo:prague-cz")
                .jsonPath("$.forecastHorizonDays").isEqualTo(7)
                .jsonPath("$.candidateWindowsEvaluated").isNumber()
                .jsonPath("$.prototype").doesNotExist()
                .jsonPath("$.ephemerisSource").doesNotExist()
                .jsonPath("$.diagnostics").doesNotExist()
                .jsonPath("$.opportunities[0].suggestedAt").exists()
                .jsonPath("$.opportunities[0].weather.sourceResolution").isEqualTo("hourly")
                .jsonPath("$.opportunities[0].links.ics")
                .value(String.class, value -> assertTrue(value.startsWith("/o/prague-cz-")));
    }

    @Test
    void mapsUnsupportedFixtureLocationToInvalidRequest() {
        webTestClient.post().uri("/api/opportunities/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "locationId": "amsterdam-nl",
                          "start": "2026-06-29",
                          "forecastHorizonDays": 7,
                          "maxMoonAltitudeDegrees": 12,
                          "limit": 5
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("Unsupported location for this prototype: amsterdam-nl");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            /api/opportunities/search | [] | Opportunity search request must be a JSON object.
            /api/opportunities/search | {} | locationId is required in the opportunity search request.
            /api/opportunities/search | {"locationId": "prague-cz"} | start is required in the opportunity search request.
            /api/opportunities/search | {"locationId": "prague-cz", "start": "2026-06-29"} | forecastHorizonDays is required in the opportunity search request.
            /api/opportunities/search | {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7} | maxMoonAltitudeDegrees is required in the opportunity search request.
            /api/opportunities/search | {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 12} | limit is required in the opportunity search request.
            /api/opportunities/search | {"locationId": ""} | locationId must be a non-empty string in the opportunity search request.
            /api/opportunities/search | {"locationId": "prague-cz", "start": "not-a-date", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 12, "limit": 5} | Invalid --start value: not-a-date
            /api/opportunities/search | {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 0, "maxMoonAltitudeDegrees": 12, "limit": 5} | forecastHorizonDays must be between 1 and 30.
            /api/opportunities/search | {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 12, "limit": 0} | limit must be between 1 and 100.
            /api/opportunities/search | {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 91, "limit": 5} | maxMoonAltitudeDegrees must be between 0.0 and 90.0.
            /api/opportunities | {"q":"Prague","preferences":{"version":1,"time":{"mode":"local_clock","windows":[{"start":"00:00","end":"04:00"}]}}} | Invalid opportunity preferences: local_clock mode requires a window and no light buckets.
            """)
    void mapsInvalidRequestBodiesToInvalidRequest(String uri, String body, String message) {
        webTestClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo(message);
    }

    @Test
    void mapsMalformedJsonToInvalidRequest() {
        webTestClient.post().uri("/api/opportunities/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("Request body must be valid JSON.");
    }

    private JsonNode productPostOk(String body) throws JacksonException {
        return responseJson(webTestClient.post().uri("/api/opportunities")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isOk(), true);
    }

    private static JsonNode responseJson(
            WebTestClient.ResponseSpec response,
            boolean noStore
    ) throws JacksonException {
        if (noStore) {
            response.expectHeader().valueEquals("Cache-Control", "no-store");
        }
        String body = response.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        assertNotNull(body);
        return MAPPER.readTree(body);
    }

}
