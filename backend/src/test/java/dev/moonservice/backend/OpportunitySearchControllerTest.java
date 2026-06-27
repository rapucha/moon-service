package dev.moonservice.backend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
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
                "moon.weather.provider=open-meteo"
        })
@AutoConfigureWebTestClient
class OpportunitySearchControllerTest {
    @Autowired
    private WebTestClient webTestClient;

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
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.location.id").isEqualTo("prague-cz")
                .jsonPath("$.forecastHorizonDays").isEqualTo(7)
                .jsonPath("$.startsAt").exists()
                .jsonPath("$.maxMoonAltitudeDegrees").isEqualTo(90.0)
                .jsonPath("$.opportunities[0].suggestedAt").exists();
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
                    assertTrue(body.contains("Find a Moon window near a city"));
                    assertTrue(body.contains("Privacy and caveats"));
                });
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
                    assertTrue(body.contains("moonService.recentSearches.v1"));
                    assertTrue(body.contains("/api/opportunities?q="));
                    assertTrue(body.contains("/api/opportunities?locationId="));
                });

        webTestClient.get()
                .uri("/styles.css")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/css")
                .expectBody(String.class)
                .value(body -> assertTrue(body.contains(".opportunity-card")));
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
