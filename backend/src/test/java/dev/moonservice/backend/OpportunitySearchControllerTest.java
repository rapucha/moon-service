package dev.moonservice.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.backend.observability.RequestLoggingFilter;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import dev.moonservice.backend.weather.TestWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.admin.token=test-admin-token",
                "moon.build.revision=test-revision"
        })
@AutoConfigureWebTestClient
class OpportunitySearchControllerTest {
    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OpenMeteoObservability openMeteoObservability;

    @TestConfiguration
    static class TestOpenMeteoLocationResolverConfiguration {
        @Bean
        @Primary
        LocationResolver testOpenMeteoLocationResolver() {
            return new TestOpenMeteoLocationResolver();
        }

        @Bean
        @Primary
        WeatherForecastProvider testWeatherForecastProvider() {
            return new TestWeatherForecastProvider();
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
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(
                        "Moon-Data-Attribution",
                        "Weather data: Open-Meteo, CC BY 4.0; location data: Open-Meteo Geocoding, "
                                + "based on GeoNames, CC BY-NC 4.0; adapted and aggregated by Moon Service.")
                .expectHeader().valueEquals(
                        "Link",
                        "<https://open-meteo.com/>; rel=\"describedby\"; title=\"Open-Meteo\"",
                        "<https://www.geonames.org/>; rel=\"describedby\"; title=\"GeoNames\"",
                        "<https://creativecommons.org/licenses/by/4.0/>; "
                                + "rel=\"license\"; title=\"CC BY 4.0 weather data\"",
                        "<https://creativecommons.org/licenses/by-nc/4.0/>; "
                                + "rel=\"license\"; title=\"CC BY-NC 4.0 location data\"")
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
                    assertTrue(body.contains("Privacy and caveats"));
                    assertTrue(body.contains("The backend sends the location text you submit to Open-Meteo"));
                    assertTrue(body.contains("href=\"https://open-meteo.com/en/terms\""));
                    assertTrue(body.contains("href=\"https://open-meteo.com/\""));
                    assertTrue(body.contains("href=\"https://www.geonames.org/\""));
                    assertTrue(body.contains("href=\"https://creativecommons.org/licenses/by/4.0/\""));
                    assertTrue(body.contains("href=\"https://creativecommons.org/licenses/by-nc/4.0/\""));
                    assertTrue(body.contains("Moon Service adapts and aggregates these data and applies its own scoring."));
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
                    assertTrue(body.contains("The backend sends the location text you submit to Open-Meteo"));
                    assertTrue(body.contains("href=\"https://open-meteo.com/en/terms\""));
                    assertTrue(body.contains("href=\"https://open-meteo.com/\""));
                    assertTrue(body.contains("href=\"https://www.geonames.org/\""));
                    assertTrue(body.contains("href=\"https://creativecommons.org/licenses/by/4.0/\""));
                    assertTrue(body.contains("href=\"https://creativecommons.org/licenses/by-nc/4.0/\""));
                    assertTrue(body.contains("Moon Service adapts and aggregates these data and applies its own scoring."));
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
                .expectHeader().valueEquals(
                        "Moon-Data-Attribution",
                        "Weather data: Open-Meteo, CC BY 4.0; location data: Open-Meteo Geocoding, "
                                + "based on GeoNames, CC BY-NC 4.0; adapted and aggregated by Moon Service.")
                .expectHeader().valueEquals(
                        "Link",
                        "<https://open-meteo.com/>; rel=\"describedby\"; title=\"Open-Meteo\"",
                        "<https://www.geonames.org/>; rel=\"describedby\"; title=\"GeoNames\"",
                        "<https://creativecommons.org/licenses/by/4.0/>; "
                                + "rel=\"license\"; title=\"CC BY 4.0 weather data\"",
                        "<https://creativecommons.org/licenses/by-nc/4.0/>; "
                                + "rel=\"license\"; title=\"CC BY-NC 4.0 location data\"")
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
            [] | Opportunity search request must be a JSON object.
            {} | locationId is required in the opportunity search request.
            {"locationId": "prague-cz"} | start is required in the opportunity search request.
            {"locationId": "prague-cz", "start": "2026-06-29"} | forecastHorizonDays is required in the opportunity search request.
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7} | maxMoonAltitudeDegrees is required in the opportunity search request.
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 12} | limit is required in the opportunity search request.
            {"locationId": ""} | locationId must be a non-empty string in the opportunity search request.
            {"locationId": "prague-cz", "start": "not-a-date", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 12, "limit": 5} | Invalid --start value: not-a-date
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 0, "maxMoonAltitudeDegrees": 12, "limit": 5} | forecastHorizonDays must be between 1 and 30.
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 12, "limit": 0} | limit must be between 1 and 100.
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 91, "limit": 5} | maxMoonAltitudeDegrees must be between 0.0 and 90.0.
            """)
    void mapsInvalidRequestBodiesToInvalidRequest(String body, String message) {
        webTestClient.post().uri("/api/opportunities/search")
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
}
