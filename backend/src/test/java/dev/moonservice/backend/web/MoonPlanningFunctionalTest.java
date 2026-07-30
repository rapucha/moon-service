package dev.moonservice.backend.web;

import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.backend.weather.TestWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.LocalClockWindow;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimePreference;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.window.MoonWindow;
import dev.moonservice.scoringprototype.window.OpportunityHardFilter;
import dev.moonservice.scoringprototype.window.WindowGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.hosted-alpha.enabled=false",
                "moon.build.revision=planning-test"
        })
@AutoConfigureWebTestClient(timeout = "15s")
@ExtendWith(OutputCaptureExtension.class)
@Tag("functional")
class MoonPlanningFunctionalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant CAPTURED_AT = Instant.parse("2026-10-24T12:00:00Z");
    private static final String PLANNING_PATH = "/api/opportunities/planning";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private LocationResolver locationResolver;

    @Autowired
    private WeatherForecastProvider weatherForecastProvider;

    @TestConfiguration
    static class PlanningTestConfiguration {
        @Bean
        @Primary
        LocationResolver planningLocationResolver() {
            return spy(new TestOpenMeteoLocationResolver());
        }

        @Bean
        @Primary
        WeatherForecastProvider planningWeatherProvider() {
            return spy(new TestWeatherForecastProvider());
        }

        @Bean
        @Primary
        Clock planningClock() {
            return Clock.fixed(CAPTURED_AT, ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void clearProviderInvocations() {
        clearInvocations(locationResolver, weatherForecastProvider);
    }

    @Test
    void returnsOneWeatherFreeWindowAndBoundedWarnings(CapturedOutput output) throws JacksonException {
        String extras = IntStream.range(0, 21)
                .mapToObj(index -> "\"extra" + index + "\":{\"child\":\"private-marker\"}")
                .collect(Collectors.joining(","));
        JsonNode response = planningPost("""
                {"locationId":"prague-cz","preferences":{"version":1,
                  "private-marker":{"child":"private-marker"},%s}}
                """.formatted(extras));

        assertEquals("ok", response.path("status").asString());
        assertEquals(CAPTURED_AT.toString(), response.path("generatedAt").asString());
        assertEquals(CAPTURED_AT.toString(), response.path("startsAt").asString());
        assertEquals(CAPTURED_AT.plus(Duration.ofDays(365)).toString(), response.path("endsAt").asString());
        assertEquals(365, response.path("planningHorizonDays").intValue());
        assertEquals("prague-cz", response.at("/location/id").asString());
        assertEquals("real_location", response.at("/location/kind").asString());
        assertEquals("Europe/Prague", response.at("/location/timezone").asString());
        assertFalse(response.path("location").has("latitude"));
        assertEquals(1, response.path("appliedPreferenceVersion").intValue());
        assertTrue(response.path("normalizedActiveFilters").isEmpty());
        assertEquals(22, response.path("ignoredPreferenceFieldCount").intValue());
        assertEquals(20, response.path("ignoredPreferenceFields").size());
        assertEquals(2, response.path("additionalIgnoredPreferenceFieldCount").intValue());

        JsonNode window = response.path("nextPlanningWindow");
        assertTrue(window.isObject());
        assertFalse(Instant.parse(window.path("startsAt").asString()).isBefore(CAPTURED_AT));
        assertTrue(Instant.parse(window.path("suggestedAt").asString())
                .isBefore(CAPTURED_AT.plus(Duration.ofDays(365))));
        assertFalse(Instant.parse(window.path("endsAt").asString())
                .isAfter(CAPTURED_AT.plus(Duration.ofDays(365))));
        assertEquals("Europe/Prague", window.path("localTimeZone").asString());
        assertTrue(window.at("/moon/altitudeDegrees").isNumber());
        assertTrue(window.at("/moon/azimuthDegrees").isNumber());
        assertTrue(window.at("/moon/illuminationPercent").isNumber());
        assertTrue(window.at("/moon/phaseAngleDegrees").isNumber());
        assertTrue(window.path("moon").has("brightLimbTiltDegrees"));
        assertTrue(window.path("moon").has("northPoleTiltDegrees"));
        assertTrue(window.at("/moon/phaseName").isString());
        assertTrue(window.at("/sun/altitudeDegrees").isNumber());
        assertTrue(window.at("/sun/azimuthDegrees").isNumber());
        assertTrue(window.at("/sun/lightBucket").isString());
        JsonNode moonPass = window.path("moonPass");
        assertMoonPass(moonPass, CAPTURED_AT, CAPTURED_AT.plus(Duration.ofDays(365)));
        assertExactPassSamples(moonPass);
        assertFalse(moonPass.has("azimuthMatchIntervals"));
        assertFalse(response.has("emptyReason"));
        assertEquals(Set.of(
                "status", "generatedAt", "startsAt", "endsAt", "planningHorizonDays",
                "location", "appliedPreferenceVersion", "normalizedActiveFilters",
                "ignoredPreferenceFields", "ignoredPreferenceFieldCount",
                "additionalIgnoredPreferenceFieldCount", "nextPlanningWindow"),
                response.propertyNames());
        assertEquals(
                Set.of("id", "kind", "displayName", "timezone", "countryCode"),
                response.path("location").propertyNames());
        assertEquals(Set.of(
                "id", "windowKind", "moonPass", "startsAt", "suggestedAt", "endsAt",
                "localTimeZone", "moon", "sun"),
                window.propertyNames());
        assertEquals(Set.of(
                "altitudeDegrees", "azimuthDegrees", "illuminationPercent",
                "phaseAngleDegrees", "brightLimbTiltDegrees", "northPoleTiltDegrees", "phaseName"),
                window.path("moon").propertyNames());
        assertEquals(
                Set.of("altitudeDegrees", "azimuthDegrees", "lightBucket"),
                window.path("sun").propertyNames());

        assertTrue(output.getOut().contains(
                "ignored_preference_fields preferenceVersion=1 count=22 truncated=true"));
        assertFalse(output.getOut().contains("private-marker"));
        assertFalse(output.getOut().contains("prague-cz"));
        verify(locationResolver).resolveLocationId("prague-cz");
        verifyNoInteractions(weatherForecastProvider);
    }

    @Test
    void completesTheFullCompiledNoMatchInterval() throws JacksonException {
        long startedNanos = System.nanoTime();
        JsonNode response = planningPost("""
                {"locationId":"prague-cz","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":89,"maximum":90}}}
                """);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

        assertEquals("ok", response.path("status").asString());
        assertEquals(365, response.path("planningHorizonDays").intValue());
        assertEquals(CAPTURED_AT.plus(Duration.ofDays(365)).toString(), response.path("endsAt").asString());
        assertTrue(response.has("nextPlanningWindow"));
        assertTrue(response.path("nextPlanningWindow").isNull());
        assertEquals("no_planning_date", response.at("/emptyReason/code").asString());
        assertEquals(
                "No matching Moon date was found in the next 365 days.",
                response.at("/emptyReason/text").asString());
        assertTrue(elapsedMillis > 0);
        verify(locationResolver).resolveLocationId("prague-cz");
        verifyNoInteractions(weatherForecastProvider);
    }

    @Test
    void returnsTheFirstCombinedMatchEvenWhenItIsMoreThanThreeHundredDaysAway()
            throws JacksonException {
        JsonNode response = planningPost("""
                {"locationId":"prague-cz","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":0,"maximum":2},
                  "time":{"mode":"local_clock","window":{"start":"20:50","end":"21:00"}},
                  "namedPhases":["waning_gibbous"],
                  "brightLimbOrientationDegrees":[{"start":250,"end":260}]}}
                """);

        JsonNode window = response.path("nextPlanningWindow");
        assertEquals("ok", response.path("status").asString());
        assertTrue(window.isObject());
        assertEquals("2027-08-20T18:53:53.585937500Z", window.path("startsAt").asString());
        assertEquals("2027-08-20T18:59:59.796875Z", window.path("suggestedAt").asString());
        assertEquals("2027-08-20T18:59:59.796875Z", window.path("endsAt").asString());
        Instant suggestedAt = Instant.parse(window.path("suggestedAt").asString());
        assertTrue(suggestedAt.isAfter(CAPTURED_AT.plus(Duration.ofDays(300))));
        LocalTime localTime = suggestedAt.atZone(ZoneId.of("Europe/Prague")).toLocalTime();
        assertFalse(localTime.isBefore(LocalTime.of(20, 50)));
        assertTrue(localTime.isBefore(LocalTime.of(21, 0)));
        assertTrue(window.at("/moon/altitudeDegrees").doubleValue() >= 0.0);
        assertTrue(window.at("/moon/altitudeDegrees").doubleValue() <= 2.0);
        assertEquals("waning_gibbous", window.at("/moon/phaseName").asString());
        assertTrue(window.at("/moon/brightLimbTiltDegrees").doubleValue() >= 250.0);
        assertTrue(window.at("/moon/brightLimbTiltDegrees").doubleValue() <= 260.0);
        assertEquals(4, response.path("normalizedActiveFilters").size());
        verify(locationResolver).resolveLocationId("prague-cz");
        verifyNoInteractions(weatherForecastProvider);
    }

    @Test
    void reselectsInsideTheIntervalWhenTheBestFilteredSampleIsTheExclusiveEndpoint()
            throws JacksonException {
        JsonNode response = planningPost("""
                {"locationId":"prague-cz","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":14,"maximum":16},
                  "azimuthDegrees":{"included":{"start":270,"end":272}},
                  "time":{"mode":"local_clock","window":{"start":"13:55","end":"14:01"}},
                  "namedPhases":["waning_crescent"],
                  "brightLimbOrientationDegrees":[{"start":292,"end":294}]}}
                """);

        JsonNode window = response.path("nextPlanningWindow");
        Instant suggestedAt = Instant.parse(window.path("suggestedAt").asString());
        LocalTime localTime = suggestedAt.atZone(ZoneId.of("Europe/Prague")).toLocalTime();
        assertEquals(response.path("endsAt").asString(), window.path("endsAt").asString());
        assertEquals("2027-10-24T11:55:00.007812500Z", window.path("suggestedAt").asString());
        assertTrue(suggestedAt.isBefore(Instant.parse(response.path("endsAt").asString())));
        assertFalse(localTime.isBefore(LocalTime.of(13, 55)));
        assertTrue(localTime.isBefore(LocalTime.of(14, 1)));
        double altitude = window.at("/moon/altitudeDegrees").doubleValue();
        assertTrue(altitude >= 14.0 && altitude <= 16.0);
        double azimuth = window.at("/moon/azimuthDegrees").doubleValue();
        assertTrue(azimuth >= 270.0 && azimuth <= 272.0);
        assertEquals("waning_crescent", window.at("/moon/phaseName").asString());
        double limb = window.at("/moon/brightLimbTiltDegrees").doubleValue();
        assertTrue(limb >= 292.0 && limb <= 294.0);
        MoonSample expected = new EphemerisSampler().sampleAt(prototypeLocation(), suggestedAt);
        assertEquals(expected.brightLimbTiltDegrees(), window.at("/moon/brightLimbTiltDegrees").doubleValue());
        assertEquals(expected.northPoleTiltDegrees(), window.at("/moon/northPoleTiltDegrees").doubleValue());
        assertEquals(expected.sunAzimuthDegrees(), window.at("/sun/azimuthDegrees").doubleValue());
        JsonNode moonPass = window.path("moonPass");
        assertEquals(response.path("endsAt").asString(), moonPass.path("endsAt").asString());
        assertTrue(moonPass.path("azimuthMatchIntervals").isArray());
        assertTrue(moonPass.path("azimuthMatchIntervals").valueStream().anyMatch(interval ->
                !suggestedAt.isBefore(Instant.parse(interval.path("startsAt").asString()))
                        && !suggestedAt.isAfter(Instant.parse(interval.path("endsAt").asString()))));
        assertEquals(5, response.path("normalizedActiveFilters").size());
        String altitudeBand = altitude <= 12.0 ? "low" : altitude <= 40.0 ? "context" : "high_context";
        assertTrue(window.path("windowKind").asString().endsWith("_" + altitudeBand));
        verify(locationResolver).resolveLocationId("prague-cz");
        verifyNoInteractions(weatherForecastProvider);
    }

    @Test
    void keepsLocationAndDependencyFailuresDistinct() throws JacksonException {
        JsonNode notFound = planningPost("""
                {"locationId":"unknown-location","preferences":{"version":1}}
                """);
        assertEquals("location_not_found", notFound.path("status").asString());
        assertEquals("No matching location found.", notFound.path("message").asString());
        assertFalse(notFound.has("nextPlanningWindow"));

        doReturn(LocationResolution.temporarilyUnavailable())
                .when(locationResolver).resolveLocationId("temporarily-unavailable");
        JsonNode unavailable = responseJson(webTestClient.post().uri(PLANNING_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"locationId":"temporarily-unavailable","preferences":{"version":1}}
                        """)
                .exchange()
                .expectStatus().isEqualTo(503));
        assertEquals("temporarily_unavailable", unavailable.path("status").asString());
        assertEquals("Location lookup is temporarily unavailable.", unavailable.path("message").asString());
        verifyNoInteractions(weatherForecastProvider);
    }

    @ParameterizedTest
    @MethodSource("invalidPlanningRequests")
    void rejectsInvalidPlanningBodiesBeforeProviderWork(String body, String message) {
        webTestClient.post().uri(PLANNING_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo(message);
        verifyNoInteractions(locationResolver, weatherForecastProvider);
    }

    static Stream<Arguments> invalidPlanningRequests() {
        return Stream.of(
                Arguments.of("{}", "locationId is required."),
                Arguments.of("{\"locationId\":\"prague-cz\"}", "preferences is required."),
                Arguments.of(
                        "{\"locationId\":\"prague-cz\",\"q\":\"Prague\",\"preferences\":{\"version\":1}}",
                        "Request body contains an unknown field."),
                Arguments.of(
                        "{\"locationId\":\"prague-cz\",\"horizon\":12,\"preferences\":{\"version\":1}}",
                        "Request body contains an unknown field."),
                Arguments.of(
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":2}}",
                        "preferences.version must be 1."),
                Arguments.of(
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1,"
                                + "\"altitudeDegrees\":{\"minimum\":20,\"maximum\":10}}}",
                        "Invalid opportunity preferences: altitude minimum must not exceed maximum."),
                Arguments.of("[]", "Request body must be a JSON object."),
                Arguments.of("{", "Request body must be valid JSON."));
    }

    @Test
    void enforcesMediaTypeAndKnownAndStreamedBodyLimits() {
        expectPlanningError(
                webTestClient.post().uri(PLANNING_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .bodyValue("{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1}}")
                        .exchange(),
                415,
                "unsupported_media_type");
        expectPlanningError(
                webTestClient.post().uri(PLANNING_PATH)
                        .bodyValue("{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1}}")
                        .exchange(),
                415,
                "unsupported_media_type");
        String large = "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1},\"padding\":\""
                + "x".repeat(16_384) + "\"}";
        expectPlanningError(
                webTestClient.post().uri(PLANNING_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(large)
                        .exchange(),
                413,
                "request_too_large");
        expectPlanningError(
                webTestClient.post().uri(PLANNING_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Flux.just(large), String.class)
                        .exchange(),
                413,
                "request_too_large");
        webTestClient.post().uri(PLANNING_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML)
                .bodyValue("{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1}}")
                .exchange()
                .expectStatus().isEqualTo(406)
                .expectHeader().valueEquals("Cache-Control", "no-store");
        verifyNoInteractions(locationResolver, weatherForecastProvider);
    }

    @Test
    void combinesActiveFiltersInsteadOfTreatingTheirMatchesAsOneResult() {
        Instant startsAt = Instant.parse("2025-12-31T23:00:00Z");
        Instant endsAt = startsAt.plus(Duration.ofMinutes(10));
        Location location = prototypeLocation();
        WindowGenerator.SampleProvider samples = instant -> separatedFilterSample(startsAt, instant);
        MoonWindow window = new MoonWindow(
                location,
                "moonrise_low",
                startsAt,
                endsAt,
                startsAt,
                samples.sampleAt(startsAt),
                samples.sampleAt(startsAt.plus(Duration.ofMinutes(5))),
                samples.sampleAt(endsAt),
                endsAt,
                List.of(samples.sampleAt(startsAt), samples.sampleAt(endsAt)),
                List.of(samples.sampleAt(startsAt), samples.sampleAt(endsAt)));
        OpportunityPreferences altitudeOnly = preferences(
                new AltitudeRange(0.0, 10.0), null);
        OpportunityPreferences timeOnly = preferences(
                null,
                new TimePreference(
                        TimeMode.LOCAL_CLOCK,
                        new LocalClockWindow(LocalTime.of(0, 5), LocalTime.of(0, 10)),
                        null));
        OpportunityPreferences combined = preferences(
                altitudeOnly.altitudeDegrees(), timeOnly.time());
        OpportunityHardFilter filter = new OpportunityHardFilter();

        assertFalse(filter.filter(
                location, List.of(window), samples, ignored -> 0.25, altitudeOnly, startsAt)
                .windows().isEmpty());
        assertFalse(filter.filter(
                location, List.of(window), samples, ignored -> 0.25, timeOnly, startsAt)
                .windows().isEmpty());
        assertTrue(filter.filter(
                location, List.of(window), samples, ignored -> 0.25, combined, startsAt)
                .windows().isEmpty());
    }

    @Test
    void exactGeneratorUsesInstantBoundsAcrossTheDaylightSavingChange() {
        Instant startsAt = CAPTURED_AT;
        Instant endsAt = startsAt.plus(Duration.ofDays(2));
        Location location = prototypeLocation();
        AtomicReference<Instant> latestSample = new AtomicReference<>(startsAt);
        WindowGenerator.SampleProvider samples = instant -> {
            latestSample.accumulateAndGet(instant, (left, right) -> left.compareTo(right) >= 0 ? left : right);
            double hours = Duration.between(startsAt, instant).toSeconds() / 3_600.0;
            double moonAltitude = 30.0 * Math.sin(2.0 * Math.PI * (hours - 6.0) / 24.0);
            double sunAltitude = 30.0 * Math.sin(2.0 * Math.PI * (hours - 12.0) / 24.0);
            return new MoonSample(
                    instant,
                    moonAltitude,
                    (hours * 15.0 + 360.0) % 360.0,
                    50.0,
                    90.0,
                    0.0,
                    sunAltitude,
                    (hours * 15.0 + 180.0) % 360.0);
        };

        List<MoonWindow> windows =
                new WindowGenerator().findWindows(location, startsAt, endsAt, 90.0, samples);

        assertFalse(windows.isEmpty());
        assertEquals(endsAt, latestSample.get());
        assertTrue(windows.stream().allMatch(window ->
                !window.startsAt().isBefore(startsAt)
                        && !window.endsAt().isAfter(endsAt)
                        && window.suggested().instant().isBefore(endsAt)));
        assertEquals(Duration.ofHours(48), Duration.between(startsAt, endsAt));
    }

    private JsonNode planningPost(String body) throws JacksonException {
        return responseJson(webTestClient.post().uri(PLANNING_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk());
    }

    private static JsonNode responseJson(WebTestClient.ResponseSpec response) throws JacksonException {
        String body = response
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(body);
        return MAPPER.readTree(body);
    }

    private static void expectPlanningError(
            WebTestClient.ResponseSpec response,
            int status,
            String code
    ) {
        response.expectStatus().isEqualTo(status)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(code);
    }

    private static void assertMoonPass(JsonNode pass, Instant horizonStart, Instant horizonEnd) {
        assertEquals(Set.of("id", "startsAt", "endsAt", "path"), pass.propertyNames());
        Instant startsAt = Instant.parse(pass.path("startsAt").asString());
        Instant endsAt = Instant.parse(pass.path("endsAt").asString());
        assertFalse(startsAt.isBefore(horizonStart));
        assertFalse(endsAt.isAfter(horizonEnd));
        JsonNode path = pass.path("path");
        assertEquals(Set.of("start", "end", "samples"), path.propertyNames());
        JsonNode samples = path.path("samples");
        assertTrue(samples.isArray());
        assertTrue(samples.size() >= 5);
        assertEquals(pass.path("startsAt").asString(), path.at("/start/at").asString());
        assertEquals(pass.path("endsAt").asString(), path.at("/end/at").asString());
        assertEquals(path.path("start"), samples.get(0));
        assertEquals(path.path("end"), samples.get(samples.size() - 1));
        Instant previous = null;
        for (int index = 0; index < samples.size(); index++) {
            JsonNode point = samples.get(index);
            assertMoonPathPoint(point);
            Instant at = Instant.parse(point.path("at").asString());
            assertFalse(at.isBefore(startsAt));
            assertFalse(at.isAfter(endsAt));
            assertTrue(previous == null || at.isAfter(previous));
            assertEquals(index == 0 ? "start" : index == samples.size() - 1 ? "end" : "path",
                    point.path("role").asString());
            previous = at;
        }
    }

    private static void assertExactPassSamples(JsonNode pass) {
        Location location = prototypeLocation();
        EphemerisSampler ephemeris = new EphemerisSampler();
        MoonWindow expected = new WindowGenerator().findWindows(
                        location,
                        CAPTURED_AT,
                        CAPTURED_AT.plus(Duration.ofDays(365)),
                        90.0,
                        instant -> ephemeris.sampleAt(location, instant)).stream()
                .filter(window -> window.passId().equals(pass.path("id").asString()))
                .findFirst()
                .orElseThrow();
        List<MoonSample> expectedSamples = expected.passPathSamples();
        JsonNode actualSamples = pass.at("/path/samples");
        assertEquals(expectedSamples.size(), actualSamples.size());
        for (int index = 0; index < expectedSamples.size(); index++) {
            MoonSample sample = expectedSamples.get(index);
            JsonNode point = actualSamples.get(index);
            assertEquals(sample.instant().toString(), point.path("at").asString());
            assertEquals(sample.moonAltitudeDegrees(), point.path("altitudeDegrees").doubleValue());
            assertEquals(sample.moonAzimuthDegrees(), point.path("azimuthDegrees").doubleValue());
            assertEquals(sample.moonPhaseAngleDegrees(), point.path("moonPhaseAngleDegrees").doubleValue());
            assertNullableDouble(sample.brightLimbTiltDegrees(), point.path("brightLimbTiltDegrees"));
            assertNullableDouble(sample.northPoleTiltDegrees(), point.path("northPoleTiltDegrees"));
            assertEquals(sample.sunAltitudeDegrees(), point.path("sunAltitudeDegrees").doubleValue());
            assertEquals(sample.sunAzimuthDegrees(), point.path("sunAzimuthDegrees").doubleValue());
            assertEquals(ScoringModel.lightBucket(sample.sunAltitudeDegrees()),
                    point.path("lightBucket").asString());
        }
    }

    private static void assertNullableDouble(Double expected, JsonNode actual) {
        if (expected == null) {
            assertTrue(actual.isNull());
        } else {
            assertEquals(expected.doubleValue(), actual.doubleValue());
        }
    }

    private static void assertMoonPathPoint(JsonNode point) {
        assertEquals(Set.of(
                "at", "altitudeDegrees", "azimuthDegrees", "moonPhaseAngleDegrees",
                "brightLimbTiltDegrees", "northPoleTiltDegrees", "sunAltitudeDegrees",
                "sunAzimuthDegrees", "lightBucket", "role"), point.propertyNames());
        assertTrue(point.path("altitudeDegrees").isNumber());
        assertTrue(point.path("azimuthDegrees").isNumber());
        assertTrue(point.path("moonPhaseAngleDegrees").isNumber());
        assertTrue(point.has("brightLimbTiltDegrees"));
        assertTrue(point.path("brightLimbTiltDegrees").isNull()
                || point.path("brightLimbTiltDegrees").isNumber());
        assertTrue(point.has("northPoleTiltDegrees"));
        assertTrue(point.path("northPoleTiltDegrees").isNull()
                || point.path("northPoleTiltDegrees").isNumber());
        assertTrue(point.path("sunAltitudeDegrees").isNumber());
        assertTrue(point.path("sunAzimuthDegrees").isNumber());
        assertTrue(point.path("lightBucket").isString());
    }

    private static OpportunityPreferences preferences(
            AltitudeRange altitude,
            TimePreference time
    ) {
        return new OpportunityPreferences(
                OpportunityPreferences.VERSION,
                altitude,
                null,
                time,
                null,
                null);
    }

    private static MoonSample separatedFilterSample(Instant anchor, Instant instant) {
        boolean early = instant.isBefore(anchor.plus(Duration.ofMinutes(5)));
        return new MoonSample(
                instant,
                early ? 5.0 : 20.0,
                90.0,
                50.0,
                90.0,
                0.0,
                -5.0,
                180.0);
    }

    private static Location prototypeLocation() {
        return new Location(
                "prague-cz",
                "real_location",
                "prague-cz",
                "Prague, Czechia",
                50.08804,
                14.42076,
                202,
                "Europe/Prague",
                "CZ");
    }
}
