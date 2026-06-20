package dev.moonservice.backend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class OpportunitySearchControllerTest {
    @Autowired
    private WebTestClient webTestClient;

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
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 46, "limit": 5} | maxMoonAltitudeDegrees must be between 0.0 and 45.0.
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
