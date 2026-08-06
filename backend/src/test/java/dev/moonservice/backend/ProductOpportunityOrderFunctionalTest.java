package dev.moonservice.backend;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

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
@Import(OpportunitySearchFunctionalTest.TestOpenMeteoLocationResolverConfiguration.class)
@Tag("functional")
class ProductOpportunityOrderFunctionalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRODUCT_BODY = "{\"q\":\"Prague\"}";
    private static final String VERSION_ONLY_BODY =
            "{\"q\":\"Prague\",\"preferences\":{\"version\":1}}";
    private static final String DIRECT_BODY = """
            {
              "locationId": "prague-cz",
              "start": "2026-06-29",
              "forecastHorizonDays": 7,
              "maxMoonAltitudeDegrees": 12,
              "limit": 5
            }
            """;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private LocationResolver locationResolver;

    @Autowired
    private WeatherForecastProvider weatherForecastProvider;

    @BeforeEach
    void clearProviderCalls() {
        clearInvocations(locationResolver, weatherForecastProvider);
    }

    @Test
    void preservesBestMatchAndSoonestAcrossBothProductRoutes() throws JacksonException {
        JsonNode defaultGet = productGet(null);
        JsonNode bestGet = productGet("best_match");
        JsonNode defaultPost = productPost(null, PRODUCT_BODY);
        JsonNode bestPost = productPost("best_match", PRODUCT_BODY);
        JsonNode bestVersionOnly = productPost("best_match", VERSION_ONLY_BODY);

        assertEquals(defaultGet.path("opportunities"), bestGet.path("opportunities"));
        assertEquals(defaultGet.path("opportunities"), defaultPost.path("opportunities"));
        assertEquals(defaultGet.path("opportunities"), bestPost.path("opportunities"));
        assertEquals(defaultGet.path("opportunities"), bestVersionOnly.path("opportunities"));
        assertEquals(1, bestVersionOnly.path("appliedPreferenceVersion").intValue());
        assertTrue(bestVersionOnly.path("normalizedActiveFilters").isEmpty());
        assertEquals(0, bestVersionOnly.path("excludedSampleCount").intValue());

        JsonNode soonestGet = productGet("soonest");
        JsonNode soonestPost = productPost("soonest", PRODUCT_BODY);
        JsonNode soonestVersionOnly = productPost("soonest", VERSION_ONLY_BODY);

        assertEquals(soonestGet.path("opportunities"), soonestPost.path("opportunities"));
        assertEquals(soonestGet.path("opportunities"), soonestVersionOnly.path("opportunities"));
        assertChronological(soonestGet.path("opportunities"));
        assertNotEquals(bestGet.path("opportunities"), soonestGet.path("opportunities"));
        assertTrue(Instant.parse(soonestGet.at("/opportunities/0/suggestedAt").asString())
                .isBefore(Instant.parse(bestGet.at("/opportunities/0/suggestedAt").asString())));
    }

    @Test
    void ordersActivePreferenceFragmentsBySoonest() throws JacksonException {
        JsonNode response = productPost("soonest", """
                {"q":"Prague","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":10,"maximum":12}}}
                """);

        assertTrue(response.at("/normalizedActiveFilters/altitudeDegrees").isObject());
        assertTrue(response.path("excludedSampleCount").intValue() > 0);
        assertFalse(response.path("opportunities").isEmpty());
        assertChronological(response.path("opportunities"));
        for (JsonNode opportunity : response.path("opportunities")) {
            double altitude = opportunity.at("/moon/altitudeDegrees").doubleValue();
            assertTrue(altitude >= 10.0 && altitude <= 12.0);
        }
        assertNotEquals(
                productGet("soonest").path("opportunities"),
                response.path("opportunities"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nearest"})
    void rejectsInvalidOrdersBeforeProviders(String order) {
        clearInvocations(locationResolver, weatherForecastProvider);
        invalidOrder(webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("q", "Prague")
                        .queryParam("order", order)
                        .build())
                .exchange(), false);
        verifyNoInteractions(locationResolver, weatherForecastProvider);

        clearInvocations(locationResolver, weatherForecastProvider);
        invalidOrder(webTestClient.post()
                .uri(uriBuilder -> uriBuilder.path("/api/opportunities")
                        .queryParam("order", order)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(PRODUCT_BODY)
                .exchange(), true);
        verifyNoInteractions(locationResolver, weatherForecastProvider);
    }

    @Test
    void keepsOrderOutOfTheProductPostBody() {
        clearInvocations(locationResolver, weatherForecastProvider);

        WebTestClient.ResponseSpec response = webTestClient.post()
                .uri("/api/opportunities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"q\":\"Prague\",\"order\":\"soonest\"}")
                .exchange();

        response.expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("Request body contains an unknown field.");
        verifyNoInteractions(locationResolver, weatherForecastProvider);
    }

    @Test
    void keepsDirectFixtureSearchBestMatchOrdered() throws JacksonException {
        clearInvocations(locationResolver, weatherForecastProvider);
        JsonNode ordinary = directPost("/api/opportunities/search");
        JsonNode withProductQuery = directPost("/api/opportunities/search?order=soonest");

        assertEquals(ordinary.path("opportunities"), withProductQuery.path("opportunities"));
        assertBestMatchOrdered(ordinary.path("opportunities"));
        verifyNoInteractions(locationResolver, weatherForecastProvider);
    }

    private JsonNode productGet(String order) throws JacksonException {
        return responseJson(webTestClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/api/opportunities").queryParam("q", "Prague");
                    return order == null
                            ? builder.build()
                            : builder.queryParam("order", order).build();
                })
                .exchange()
                .expectStatus().isOk(), false);
    }

    private JsonNode productPost(String order, String body) throws JacksonException {
        return responseJson(webTestClient.post()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/api/opportunities");
                    return order == null
                            ? builder.build()
                            : builder.queryParam("order", order).build();
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk(), true);
    }

    private JsonNode directPost(String uri) throws JacksonException {
        return responseJson(webTestClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(DIRECT_BODY)
                .exchange()
                .expectStatus().isOk(), false);
    }

    private static void invalidOrder(WebTestClient.ResponseSpec response, boolean noStore) {
        if (noStore) {
            response.expectHeader().valueEquals("Cache-Control", "no-store");
        }
        response.expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("order must be best_match or soonest.");
    }

    private static JsonNode responseJson(
            WebTestClient.ResponseSpec response,
            boolean noStore
    ) throws JacksonException {
        if (noStore) {
            response.expectHeader().valueEquals("Cache-Control", "no-store");
        }
        String body = response.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(body);
        return MAPPER.readTree(body);
    }

    private static void assertChronological(JsonNode opportunities) {
        for (int index = 1; index < opportunities.size(); index++) {
            JsonNode previous = opportunities.get(index - 1);
            JsonNode current = opportunities.get(index);
            Instant previousTime = Instant.parse(previous.path("suggestedAt").asString());
            Instant currentTime = Instant.parse(current.path("suggestedAt").asString());
            assertFalse(currentTime.isBefore(previousTime));
            if (currentTime.equals(previousTime)) {
                int scoreComparison = Integer.compare(
                        previous.path("score").intValue(), current.path("score").intValue());
                assertTrue(scoreComparison > 0 || scoreComparison == 0
                        && previous.path("id").asString().compareTo(current.path("id").asString()) <= 0);
            }
        }
    }

    private static void assertBestMatchOrdered(JsonNode opportunities) {
        for (int index = 1; index < opportunities.size(); index++) {
            JsonNode previous = opportunities.get(index - 1);
            JsonNode current = opportunities.get(index);
            int scoreComparison = Integer.compare(
                    previous.path("score").intValue(), current.path("score").intValue());
            assertTrue(scoreComparison > 0 || scoreComparison == 0
                    && !Instant.parse(previous.path("suggestedAt").asString())
                    .isAfter(Instant.parse(current.path("suggestedAt").asString())));
        }
    }
}
