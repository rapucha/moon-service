package dev.moonservice.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.backend.weather.TestWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.hosted-alpha.enabled=false"
        })
@AutoConfigureWebTestClient
@Tag("functional")
class CurrentMoonProductResponseFunctionalTest {
    private static final Instant AS_OF = Instant.parse("2026-06-28T22:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebTestClient webTestClient;

    @TestConfiguration
    static class CurrentMoonTestConfiguration {
        @Bean
        @Primary
        LocationResolver currentMoonLocationResolver() {
            return new TestOpenMeteoLocationResolver();
        }

        @Bean
        @Primary
        WeatherForecastProvider currentMoonWeatherForecastProvider() {
            return new TestWeatherForecastProvider();
        }

        @Bean
        @Primary
        Clock currentMoonClock() {
            return Clock.fixed(AS_OF, ZoneOffset.UTC);
        }
    }

    @Test
    void addsTheSameCurrentSnapshotToGetAndEmptyPreferenceResults() throws JacksonException {
        JsonNode get = ok(webTestClient.get().uri("/api/opportunities?q=Prague").exchange());
        JsonNode post = ok(webTestClient.post().uri("/api/opportunities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"q":"Prague","preferences":{"version":1,
                          "altitudeDegrees":{"minimum":90,"maximum":90}}}
                        """)
                .exchange());

        assertEquals("ok", get.path("status").asString());
        assertEquals(AS_OF.toString(), get.path("asOf").asString());
        assertEquals(get.path("asOf"), get.path("generatedAt"));
        assertEquals(get.path("asOf"), post.path("asOf"));
        assertEquals(get.path("currentMoon"), post.path("currentMoon"));
        assertEquals(post.path("asOf"), post.path("generatedAt"));
        assertTrue(post.path("opportunities").isEmpty());
        assertEquals(1, post.path("appliedPreferenceVersion").intValue());

        JsonNode currentMoon = get.path("currentMoon");
        assertEquals(Set.of(
                        "horizonState", "moon", "sun", "nextRiseBoundary", "nextPass", "activePass"),
                currentMoon.propertyNames());
        assertEquals("above_or_on_horizon", currentMoon.path("horizonState").asString());
        assertTrue(currentMoon.at("/moon/altitudeDegrees").doubleValue() >= 0.0);
        assertTrue(currentMoon.path("nextRiseBoundary").isNull());
        assertTrue(currentMoon.path("nextPass").isNull());
        assertEquals(Set.of(
                        "altitudeDegrees", "azimuthDegrees", "illuminationPercent",
                        "phaseAngleDegrees", "brightLimbTiltDegrees", "northPoleTiltDegrees",
                        "phaseName"),
                currentMoon.path("moon").propertyNames());
        assertEquals(Set.of("altitudeDegrees", "azimuthDegrees", "lightBucket"),
                currentMoon.path("sun").propertyNames());

        JsonNode activePass = currentMoon.path("activePass");
        assertEquals("found", activePass.at("/startBoundary/status").asString());
        assertEquals("found", activePass.at("/endBoundary/status").asString());
        assertEquals(activePass.at("/startBoundary/at"), activePass.path("representedStartsAt"));
        assertEquals(activePass.at("/endBoundary/at"), activePass.path("representedEndsAt"));

        Instant representedStart = instant(activePass, "representedStartsAt");
        Instant representedEnd = instant(activePass, "representedEndsAt");
        Instant rankedStart = instant(get, "startsAt");
        assertTrue(representedStart.isBefore(rankedStart));
        ZoneId prague = ZoneId.of(get.at("/location/timezone").asString());
        assertTrue(representedStart.atZone(prague).toLocalDate()
                .isBefore(representedEnd.atZone(prague).toLocalDate()));

        JsonNode path = activePass.path("path");
        assertEquals(activePass.path("representedStartsAt"), path.at("/start/at"));
        assertEquals("start", path.at("/start/role").asString());
        assertEquals(get.path("asOf"), path.at("/now/at"));
        assertEquals("now", path.at("/now/role").asString());
        assertEquals(activePass.path("representedEndsAt"), path.at("/end/at"));
        assertEquals("end", path.at("/end/role").asString());
        assertChronologicalUniqueSamples(path.path("samples"));
    }

    @Test
    void serializesTheSameNextRiseForGetAndPostBelowTheHorizon() throws JacksonException {
        JsonNode response = ok(webTestClient.get()
                .uri("/api/opportunities?locationId=springfield-mo-us")
                .exchange());
        JsonNode post = ok(webTestClient.post().uri("/api/opportunities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"locationId":"springfield-mo-us","preferences":{"version":1,
                          "altitudeDegrees":{"minimum":90,"maximum":90}}}
                        """)
                .exchange());

        JsonNode currentMoon = response.path("currentMoon");
        assertEquals(AS_OF.toString(), response.path("asOf").asString());
        assertEquals("below_horizon", currentMoon.path("horizonState").asString());
        assertTrue(currentMoon.at("/moon/altitudeDegrees").doubleValue() < 0.0);
        assertTrue(currentMoon.has("activePass"));
        assertTrue(currentMoon.path("activePass").isNull());
        assertEquals(currentMoon.path("nextRiseBoundary"), post.at("/currentMoon/nextRiseBoundary"));
        assertEquals(currentMoon.path("nextPass"), post.at("/currentMoon/nextPass"));
        assertTrue(currentMoon.path("nextRiseBoundary").isObject());
        assertTrue(Set.of("found", "not_found_within_range")
                .contains(currentMoon.at("/nextRiseBoundary/status").asString()));
        if ("found".equals(currentMoon.at("/nextRiseBoundary/status").asString())) {
            assertTrue(currentMoon.at("/nextRiseBoundary/at").isTextual());
        } else {
            assertFalse(currentMoon.at("/nextRiseBoundary").has("at"));
        }

        JsonNode nextPass = currentMoon.path("nextPass");
        assertTrue(nextPass.isObject());
        assertEquals(currentMoon.path("nextRiseBoundary"), nextPass.path("startBoundary"));
        assertTrue(Set.of("found", "not_found_within_range")
                .contains(nextPass.at("/endBoundary/status").asString()));
        assertEquals(nextPass.path("representedStartsAt"), nextPass.at("/path/start/at"));
        assertEquals(nextPass.path("representedEndsAt"), nextPass.at("/path/end/at"));
        assertFalse(nextPass.at("/path").has("now"));
        assertChronologicalSamplesWithoutNow(nextPass.at("/path/samples"));
    }

    @Test
    void omitsProductOnlyFieldsFromFixtureAndStatusEnvelopes() throws JacksonException {
        JsonNode fixture = ok(webTestClient.post().uri("/api/opportunities/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"locationId":"prague-cz","start":"2026-06-29",
                          "forecastHorizonDays":7,"maxMoonAltitudeDegrees":12,"limit":5}
                        """)
                .exchange());
        JsonNode ambiguous = ok(webTestClient.get()
                .uri("/api/opportunities?q=Springfield").exchange());
        JsonNode notFound = ok(webTestClient.get()
                .uri("/api/opportunities?q=Not%20A%20Real%20Test%20City").exchange());
        JsonNode invalid = body(webTestClient.get()
                .uri("/api/opportunities?q=Prague&locationId=prague-cz")
                .exchange().expectStatus().isBadRequest());

        assertEquals("ok", fixture.path("status").asString());
        assertEquals("ambiguous_location", ambiguous.path("status").asString());
        assertEquals("location_not_found", notFound.path("status").asString());
        assertEquals("invalid_request", invalid.path("status").asString());
        for (JsonNode response : Set.of(fixture, ambiguous, notFound, invalid)) {
            assertFalse(response.has("asOf"));
            assertFalse(response.has("currentMoon"));
        }
    }

    private static void assertChronologicalUniqueSamples(JsonNode samples) {
        Instant previous = null;
        int nowCount = 0;
        Set<Instant> instants = new HashSet<>();
        for (JsonNode sample : samples) {
            Instant at = Instant.parse(sample.path("at").asString());
            assertTrue(instants.add(at));
            if (previous != null) {
                assertTrue(at.isAfter(previous));
            }
            if ("now".equals(sample.path("role").asString())) {
                nowCount++;
                assertEquals(AS_OF, at);
            }
            previous = at;
        }
        assertEquals(1, nowCount);
    }

    private static void assertChronologicalSamplesWithoutNow(JsonNode samples) {
        Instant previous = null;
        Set<Instant> instants = new HashSet<>();
        for (JsonNode sample : samples) {
            Instant at = Instant.parse(sample.path("at").asString());
            assertTrue(instants.add(at));
            if (previous != null) {
                assertTrue(at.isAfter(previous));
            }
            assertFalse("now".equals(sample.path("role").asString()));
            previous = at;
        }
    }

    private JsonNode ok(WebTestClient.ResponseSpec response) throws JacksonException {
        return body(response.expectStatus().isOk());
    }

    private JsonNode body(WebTestClient.ResponseSpec response) throws JacksonException {
        String value = response.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(value);
        return MAPPER.readTree(value);
    }

    private static Instant instant(JsonNode parent, String field) {
        return Instant.parse(parent.path(field).asString());
    }
}
