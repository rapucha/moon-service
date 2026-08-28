package dev.moonservice.backend;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
class WeatherRankingProductApiFunctionalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> PREFERENCE_ONLY_FIELDS = List.of(
            "appliedPreferenceVersion",
            "normalizedActiveFilters",
            "excludedSampleCount",
            "ignoredPreferenceFields",
            "ignoredPreferenceFieldCount",
            "additionalIgnoredPreferenceFieldCount",
            "emptyReason",
            "preferenceImpact");

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

    @AfterEach
    void clearWeatherFailureStub() {
        reset(weatherForecastProvider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "42", "true", "[]", "{}"})
    void rejectsNonStringWeatherRankingBeforeProviders(String value) throws JacksonException {
        JsonNode error = productPostError("""
                {"q":"Prague","weatherRanking":%s}
                """.formatted(value));

        assertEquals("invalid_request", error.path("status").asString());
        assertFalse(error.has("appliedWeatherRanking"));
        verifyNoInteractions(locationResolver, weatherForecastProvider);
    }

    @Test
    void rejectsUnsupportedWeatherRankingWithoutEchoingIt() throws JacksonException {
        String suppliedValue = "private-weather-mode-marker";
        String body = responseBody(webTestClient.post().uri("/api/opportunities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"q":"Prague","weatherRanking":"%s"}
                        """.formatted(suppliedValue))
                .exchange()
                .expectStatus().isBadRequest(), true);

        JsonNode error = MAPPER.readTree(body);
        assertEquals("invalid_request", error.path("status").asString());
        assertFalse(error.has("appliedWeatherRanking"));
        assertFalse(body.contains(suppliedValue));
        verifyNoInteractions(locationResolver, weatherForecastProvider);
    }

    @Test
    void explicitBalancedMatchesTheOmittedProductResponseExceptForAppliedMode()
            throws JacksonException {
        JsonNode get = productGet("/api/opportunities?q=Prague");
        JsonNode omitted = productPostOk("{\"q\":\"Prague\"}");
        JsonNode explicit = productPostOk("""
                {"q":"Prague","weatherRanking":"balanced"}
                """);

        assertFalse(get.has("appliedWeatherRanking"));
        assertFalse(omitted.has("appliedWeatherRanking"));
        assertEquals("balanced", explicit.path("appliedWeatherRanking").asString());
        assertEquals(get, omitted);
        assertEquals(omitted, withoutAppliedWeatherRanking(explicit));
        verify(weatherForecastProvider, times(3)).forecastFor(any(), any(), any(), anyInt());
    }

    @Test
    void appliesEveryModeAndKeepsWeatherWhenWeatherScoresAreIgnored() throws JacksonException {
        JsonNode balanced = productPostOk("""
                {"q":"Prague","weatherRanking":"balanced"}
                """);
        JsonNode preferClear = productPostOk("""
                {"q":"Prague","weatherRanking":"prefer_clear"}
                """);
        JsonNode ignoreWeather = productPostOk("""
                {"q":"Prague","weatherRanking":"ignore_weather"}
                """);

        assertEquals("prefer_clear", preferClear.path("appliedWeatherRanking").asString());
        assertEquals("ignore_weather", ignoreWeather.path("appliedWeatherRanking").asString());
        assertNotEquals(
                balanced.at("/opportunities/0/score"),
                preferClear.at("/opportunities/0/score"));
        assertFalse(ignoreWeather.path("opportunities").isEmpty());
        PREFERENCE_ONLY_FIELDS.forEach(field -> assertFalse(ignoreWeather.has(field), field));
        for (JsonNode opportunity : ignoreWeather.path("opportunities")) {
            assertTrue(opportunity.path("weather").isObject());
            assertFalse(opportunity.path("moonPass").has("azimuthMatchIntervals"));
            assertFalse(opportunity.path("components").has("weatherFit"));
            assertFalse(opportunity.path("components").has("forecastConfidence"));
            assertTrue(opportunity.at("/scoreBasis/componentPoints").isIntegralNumber());
            assertEquals(70, opportunity.at("/scoreBasis/componentMaximum").intValue());
            assertEquals(
                    List.of("weatherFit", "forecastConfidence"),
                    textValues(opportunity.at("/scoreBasis/excludedComponents")));
        }
        verify(weatherForecastProvider, times(3)).forecastFor(any(), any(), any(), anyInt());
    }

    @Test
    void changingModeDoesNotChangeActiveHardPreferenceResults() throws JacksonException {
        String request = """
                {"q":"Prague","weatherRanking":"%s","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":10,"maximum":12},
                  "azimuthDegrees":{"included":{"start":10,"end":350}}}}
                """;
        JsonNode balanced = productPostOk(request.formatted("balanced"));
        JsonNode ignoreWeather = productPostOk(request.formatted("ignore_weather"));

        for (String field : List.of(
                "candidateWindowsEvaluated",
                "appliedPreferenceVersion",
                "normalizedActiveFilters",
                "excludedSampleCount",
                "ignoredPreferenceFields",
                "ignoredPreferenceFieldCount",
                "additionalIgnoredPreferenceFieldCount",
                "emptyReason",
                "preferenceImpact",
                "rejected")) {
            assertEquals(balanced.path(field), ignoreWeather.path(field), field);
        }
        assertEquals(balanced.path("opportunities").size(), ignoreWeather.path("opportunities").size());
        assertFalse(balanced.path("opportunities").isEmpty());
        assertFalse(balanced.at("/opportunities/0/moonPass/azimuthMatchIntervals").isEmpty());
        assertEquals(opportunityFacts(balanced), opportunityFacts(ignoreWeather));
        assertNotEquals(
                balanced.at("/opportunities/0/components"),
                ignoreWeather.at("/opportunities/0/components"));
        verify(weatherForecastProvider, times(2)).forecastFor(any(), any(), any(), anyInt());
    }

    @Test
    void omitsAppliedModeWhenLocationOrWeatherPreventsScoring() throws JacksonException {
        JsonNode ambiguous = productPostOk("""
                {"q":"Springfield","weatherRanking":"prefer_clear"}
                """);
        JsonNode notFound = productPostOk("""
                {"q":"No such place","weatherRanking":"ignore_weather"}
                """);

        assertEquals("ambiguous_location", ambiguous.path("status").asString());
        assertEquals("location_not_found", notFound.path("status").asString());
        assertFalse(ambiguous.has("appliedWeatherRanking"));
        assertFalse(notFound.has("appliedWeatherRanking"));
        verifyNoInteractions(weatherForecastProvider);

        doThrow(new WeatherForecastUnavailableException("Weather provider failed."))
                .when(weatherForecastProvider)
                .forecastFor(any(), any(), any(), anyInt());
        JsonNode unavailable = productPostUnavailable("""
                {"q":"Prague","weatherRanking":"ignore_weather"}
                """);

        assertEquals("temporarily_unavailable", unavailable.path("status").asString());
        assertFalse(unavailable.has("appliedWeatherRanking"));
        verify(weatherForecastProvider).forecastFor(any(), any(), any(), anyInt());
    }

    @Test
    void planningStillRejectsWeatherRankingBeforeProviders() throws JacksonException {
        JsonNode error = MAPPER.readTree(responseBody(
                webTestClient.post().uri("/api/opportunities/planning")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""
                                {"locationId":"prague-cz","weatherRanking":"balanced",
                                 "preferences":{"version":1}}
                                """)
                        .exchange()
                        .expectStatus().isBadRequest(),
                false));

        assertEquals("invalid_request", error.path("status").asString());
        assertFalse(error.has("appliedWeatherRanking"));
        verifyNoInteractions(locationResolver, weatherForecastProvider);
    }

    @Test
    void preservesSoonestOrderingWhileUsingTheSelectedScores() throws JacksonException {
        JsonNode balanced = productPostOk("""
                {"q":"Prague","weatherRanking":"balanced"}
                """, "soonest");
        JsonNode ignoreWeather = productPostOk("""
                {"q":"Prague","weatherRanking":"ignore_weather"}
                """, "soonest");

        assertChronological(balanced.path("opportunities"));
        assertChronological(ignoreWeather.path("opportunities"));
        assertEquals(suggestedTimes(balanced), suggestedTimes(ignoreWeather));
        assertNotEquals(
                balanced.at("/opportunities/0/score"),
                ignoreWeather.at("/opportunities/0/score"));
    }

    private JsonNode productGet(String uri) throws JacksonException {
        return responseJson(webTestClient.get().uri(uri).exchange().expectStatus().isOk(), false);
    }

    private JsonNode productPostOk(String body) throws JacksonException {
        return productPostOk(body, null);
    }

    private JsonNode productPostOk(String body, String order) throws JacksonException {
        WebTestClient.RequestBodySpec request = webTestClient.post().uri(uriBuilder -> {
            var builder = uriBuilder.path("/api/opportunities");
            return order == null ? builder.build() : builder.queryParam("order", order).build();
        });
        return responseJson(request.contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isOk(), true);
    }

    private JsonNode productPostUnavailable(String body) throws JacksonException {
        return responseJson(webTestClient.post().uri("/api/opportunities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(503), true);
    }

    private JsonNode productPostError(String body) throws JacksonException {
        return errorResponse(webTestClient.post().uri("/api/opportunities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest());
    }

    private static JsonNode errorResponse(WebTestClient.ResponseSpec response) throws JacksonException {
        return MAPPER.readTree(responseBody(response, true));
    }

    private static JsonNode responseJson(
            WebTestClient.ResponseSpec response,
            boolean noStore
    ) throws JacksonException {
        return MAPPER.readTree(responseBody(response, noStore));
    }

    private static String responseBody(
            WebTestClient.ResponseSpec response,
            boolean noStore
    ) {
        if (noStore) {
            response.expectHeader().valueEquals("Cache-Control", "no-store");
        }
        String body = response.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        assertNotNull(body);
        return body;
    }

    private static JsonNode withoutAppliedWeatherRanking(JsonNode response) {
        ObjectNode copy = (ObjectNode) response.deepCopy();
        copy.remove("appliedWeatherRanking");
        return copy;
    }

    private static JsonNode withoutScoringFields(JsonNode opportunity) {
        ObjectNode copy = (ObjectNode) opportunity.deepCopy();
        copy.remove(List.of("score", "confidence", "components", "scoreBasis", "links"));
        return copy;
    }

    private static List<JsonNode> opportunityFacts(JsonNode response) {
        return StreamSupport.stream(response.path("opportunities").spliterator(), false)
                .map(WeatherRankingProductApiFunctionalTest::withoutScoringFields)
                .sorted(Comparator.comparing(value -> value.path("id").asString()))
                .toList();
    }

    private static List<String> textValues(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false).map(JsonNode::asString).toList();
    }

    private static List<String> suggestedTimes(JsonNode response) {
        return StreamSupport.stream(response.path("opportunities").spliterator(), false)
                .map(opportunity -> opportunity.path("suggestedAt").asString())
                .toList();
    }

    private static void assertChronological(JsonNode opportunities) {
        Instant previous = null;
        for (JsonNode opportunity : opportunities) {
            Instant current = Instant.parse(opportunity.path("suggestedAt").asString());
            if (previous != null) {
                assertFalse(current.isBefore(previous));
            }
            previous = current;
        }
    }
}
