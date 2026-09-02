package dev.moonservice.backend.web;

import dev.moonservice.backend.events.MoonEventService;
import dev.moonservice.backend.events.MoonEventResponse;
import dev.moonservice.backend.events.MoonEventResponse.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class MoonEventControllerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = "/api/moon-events";

    @Test
    void returnsTheClosedEventUnionAndOnlyAggregateIgnoredFieldLogging(
            CapturedOutput output
    ) throws Exception {
        MoonEventService service = mock(MoonEventService.class);
        when(service.search(anyString(), any(), anyInt(), anyList(), anyInt()))
                .thenReturn(successResponse());

        MvcResult result = mvc(service).perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationId":"prague-cz","preferences":{"version":1,
                                  "altitudeDegrees":{"minimum":5,"maximum":10},
                                  "private-marker":{"child":"secret-value"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.events[0].kind").value("lunar_eclipse"))
                .andExpect(jsonPath("$.events[0].weather.status").value("available"))
                .andExpect(jsonPath("$.events[2].kind").value("full_moon"))
                .andExpect(jsonPath("$.events[2].qualifiers[0].kind")
                        .value("near_perigee"))
                .andExpect(jsonPath("$.events[3].localViewing").doesNotExist())
                .andExpect(jsonPath("$.events[3].weather").doesNotExist())
                .andReturn();

        JsonNode response = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(response.propertyNames()).containsExactlyInAnyOrder(
                "status", "generatedAt", "startsAt", "endsAt", "location",
                "appliedPreferenceVersion", "normalizedActiveFilters",
                "ignoredPreferenceFields", "ignoredPreferenceFieldCount",
                "additionalIgnoredPreferenceFieldCount", "events");
        assertThat(response.path("location").propertyNames()).containsExactlyInAnyOrder(
                "id", "kind", "displayName", "timezone", "countryCode");
        assertThat(response.path("events").get(0).propertyNames()).containsExactlyInAnyOrder(
                "id", "kind", "subtype", "startsAt", "maximumAt", "endsAt",
                "umbralObscurationPercent", "phases", "shadowSamples", "moonAtMaximum",
                "localVisibility", "preferenceAssessment", "weather");
        assertThat(response.at("/events/0/shadowSamples/0").propertyNames())
                .containsExactlyInAnyOrder("at", "moon", "shadow");
        assertThat(response.at("/events/0/shadowSamples/0/moon").propertyNames())
                .containsExactlyInAnyOrder(
                        "altitudeDegrees", "azimuthDegrees", "northPoleTiltDegrees");
        assertThat(response.at("/events/0/shadowSamples/0/shadow").propertyNames())
                .containsExactlyInAnyOrder(
                        "centerRightMoonRadii", "centerUpMoonRadii",
                        "umbraRadiusMoonRadii", "penumbraRadiusMoonRadii");
        assertThat(response.at("/events/0/phases/0/localVisibility").propertyNames())
                .containsExactlyInAnyOrder("status", "intervals");
        assertThat(response.at("/events/0/localVisibility").propertyNames())
                .containsExactlyInAnyOrder(
                        "status", "intervals", "selectedInterval", "displayInterval", "moonPath");
        JsonNode eclipsePath = response.at(
                "/events/0/localVisibility/moonPath/samples/0");
        assertThat(eclipsePath.propertyNames()).containsExactlyInAnyOrder(
                "at", "altitudeDegrees", "azimuthDegrees", "moonPhaseAngleDegrees",
                "brightLimbTiltDegrees", "northPoleTiltDegrees", "sunAltitudeDegrees",
                "sunAzimuthDegrees", "lightBucket", "shadow");
        assertThat(eclipsePath.path("shadow").propertyNames()).containsExactlyInAnyOrder(
                "centerRightMoonRadii", "centerUpMoonRadii",
                "umbraRadiusMoonRadii", "penumbraRadiusMoonRadii");
        assertThat(response.at("/events/0/weather").propertyNames()).containsExactlyInAnyOrder(
                "status", "forecastHourStartsAt", "summary", "cloudCoverPercent",
                "precipitationProbabilityPercent");
        assertThat(response.at("/events/1/weather").propertyNames()).containsExactly("status");

        JsonNode fullMoon = response.path("events").get(2);
        assertThat(fullMoon.propertyNames()).containsExactlyInAnyOrder(
                "id", "kind", "peakAt", "qualifiers", "localViewing",
                "preferenceAssessment", "weather");
        assertThat(fullMoon.path("qualifiers").get(0).propertyNames())
                .containsExactlyInAnyOrder(
                        "kind", "definitionVersion", "closeness",
                        "distanceKilometersAtPeak", "perigeeDistanceKilometers",
                        "apogeeDistanceKilometers");
        assertThat(fullMoon.path("localViewing").propertyNames())
                .containsExactlyInAnyOrder(
                        "intervals", "selectedInterval", "displayInterval", "moonPath");
        JsonNode fullMoonPath = fullMoon.at("/localViewing/moonPath/samples/0");
        assertThat(fullMoonPath.propertyNames()).containsExactlyInAnyOrder(
                "at", "altitudeDegrees", "azimuthDegrees", "moonPhaseAngleDegrees",
                "brightLimbTiltDegrees", "northPoleTiltDegrees", "sunAltitudeDegrees",
                "sunAzimuthDegrees", "lightBucket");
        assertThat(fullMoonPath.has("shadow")).isFalse();
        JsonNode noLocal = response.path("events").get(3);
        assertThat(noLocal.propertyNames()).containsExactlyInAnyOrder(
                "id", "kind", "peakAt", "qualifiers", "preferenceAssessment");

        verify(service).search(
                eq("prague-cz"),
                argThat(preferences -> preferences.normalizedFilters().keySet()
                        .equals(Set.of("altitudeDegrees"))),
                eq(18),
                eq(List.of("/private-marker")),
                eq(1));
        assertThat(output.getOut()).contains(
                "ignored_preference_fields preferenceVersion=1 count=1 truncated=false");
        assertThat(output.getOut()).doesNotContain("secret-value", "private-marker", "prague-cz");
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 12, 18, 24, 36})
    void passesSupportedEventHorizonsToService(int eventHorizonMonths) throws Exception {
        MoonEventService service = mock(MoonEventService.class);
        when(service.search(anyString(), any(), anyInt(), anyList(), anyInt()))
                .thenReturn(successResponse());

        mvc(service).perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationId":"prague-cz","preferences":{"version":1},
                                 "eventHorizonMonths":%d}
                                """.formatted(eventHorizonMonths)))
                .andExpect(status().isOk());

        verify(service).search(
                eq("prague-cz"), any(), eq(eventHorizonMonths), eq(List.of()), eq(0));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidBodiesAndQueryVariantsBeforeServiceWork(String uri, String body, String message)
            throws Exception {
        MoonEventService service = mock(MoonEventService.class);

        mvc(service).perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(message));
        verifyNoInteractions(service);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(PATH, "{}", "locationId is required."),
                Arguments.of(PATH, "{\"locationId\":\"prague-cz\"}", "preferences is required."),
                Arguments.of(PATH,
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":2}}",
                        "preferences.version must be 1."),
                Arguments.of(PATH,
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1},"
                                + "\"eventHorizonMonths\":\"12\"}",
                        "eventHorizonMonths must be an integer."),
                Arguments.of(PATH,
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1},"
                                + "\"eventHorizonMonths\":12.0}",
                        "eventHorizonMonths must be an integer."),
                Arguments.of(PATH,
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1},"
                                + "\"eventHorizonMonths\":true}",
                        "eventHorizonMonths must be an integer."),
                Arguments.of(PATH,
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1},"
                                + "\"eventHorizonMonths\":5}",
                        "eventHorizonMonths must be one of 6, 12, 18, 24, 36."),
                Arguments.of(PATH,
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1},"
                                + "\"eventHorizonMonths\":30}",
                        "eventHorizonMonths must be one of 6, 12, 18, 24, 36."),
                Arguments.of(PATH,
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1},"
                                + "\"eventHorizonMonths\":2147483648}",
                        "eventHorizonMonths must be an integer."),
                Arguments.of(PATH,
                        "{\"locationId\":\"prague-cz\",\"q\":\"Prague\","
                                + "\"preferences\":{\"version\":1}}",
                        "Request body contains an unknown field."),
                Arguments.of(PATH,
                        "{\"locationId\":\"prague-cz\",\"weatherRanking\":\"balanced\","
                                + "\"preferences\":{\"version\":1}}",
                        "Request body contains an unknown field."),
                Arguments.of(PATH + "?locationId=prague-cz",
                        "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1}}",
                        "Moon event requests must not use URL query parameters."),
                Arguments.of(PATH, "{", "Request body must be valid JSON."));
    }

    @Test
    void enforcesMediaBodyAndRepresentationRulesWithNoStoreErrors() throws Exception {
        MoonEventService service = mock(MoonEventService.class);
        MockMvc mvc = mvc(service);
        String valid = "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1}}";

        mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(valid))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("unsupported_media_type"));
        mvc.perform(post(PATH).content(valid))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Cache-Control", "no-store"));
        String large = "{\"locationId\":\"prague-cz\",\"preferences\":{\"version\":1},\"padding\":\""
                + "x".repeat(16_384) + "\"}";
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(large))
                .andExpect(status().isContentTooLarge())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("request_too_large"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML).content(valid))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(PATH))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Allow", "POST"));
        verifyNoInteractions(service);
    }

    @Test
    void mapsOnlyTopLevelTemporaryUnavailabilityToServiceUnavailable() throws Exception {
        MoonEventService service = mock(MoonEventService.class);
        when(service.search(anyString(), any(), anyInt(), anyList(), anyInt()))
                .thenReturn(new Status(
                        "temporarily_unavailable",
                        "2026-08-30T10:00:00Z",
                        "Location lookup is temporarily unavailable."));

        mvc(service).perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"temporary\",\"preferences\":{\"version\":1}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("temporarily_unavailable"));
    }

    private static MockMvc mvc(MoonEventService service) {
        return MockMvcBuilders.standaloneSetup(new MoonEventController(service))
                .setControllerAdvice(new OpportunitySearchErrorHandler())
                .addFilters(new HostedAlphaSurfaceFilter(false))
                .build();
    }

    private static Success successResponse() {
        LunarEclipseEvent available = eclipse("event-1", new Weather(
                "available", "2026-09-01T02:00:00Z", "partly cloudy", 38, 5));
        LunarEclipseEvent outside = eclipse(
                "event-2", outsideForecastHorizon());
        FullMoonEvent local = fullMoon("event-3", true);
        FullMoonEvent noLocal = fullMoon("event-4", false);
        return new Success(
                "ok",
                "2026-08-30T10:00:00Z",
                "2026-08-30T10:00:00Z",
                "2028-02-29T11:00:00Z",
                new MoonEventResponse.Location(
                        "prague-cz", "real_location", "Prague, Czechia", "Europe/Prague", "CZ"),
                1,
                Map.of("altitudeDegrees", Map.of("minimum", 5.0, "maximum", 10.0)),
                List.of("/private-marker"),
                1,
                0,
                List.of(available, outside, local, noLocal));
    }

    private static LunarEclipseEvent eclipse(String id, Weather weather) {
        Interval interval = interval();
        return new LunarEclipseEvent(
                id,
                "lunar_eclipse",
                "total",
                interval.startsAt(),
                "2026-09-01T02:30:00Z",
                interval.endsAt(),
                100.0,
                List.of(new EclipsePhase(
                        "penumbral", interval.startsAt(), interval.endsAt(),
                        new PhaseVisibility("fully_visible", List.of(interval)))),
                List.of(new EclipseShadowSample(
                        "2026-09-01T02:30:00Z",
                        new EclipseShadowMoon(20.0, 180.0, 12.0),
                        new EclipseShadow(-0.3, 0.4, 2.7, 4.7))),
                new MoonPosition(20.0, 180.0),
                new EventVisibility(
                        "fully_visible",
                        List.of(interval),
                        interval,
                        display(interval),
                        moonPath(interval, new EclipseShadow(-0.3, 0.4, 2.7, 4.7))),
                new PreferenceAssessment(
                        "matches", List.of(new FilterAssessment("altitudeDegrees", "matches"))),
                weather);
    }

    private static FullMoonEvent fullMoon(String id, boolean local) {
        Interval interval = interval();
        LocalViewing viewing = local
                ? new LocalViewing(
                        List.of(interval), interval, display(interval), moonPath(interval, null))
                : null;
        return new FullMoonEvent(
                id,
                "full_moon",
                "2027-01-22T12:17:50.281Z",
                List.of(new FullMoonQualifier(
                        "near_perigee",
                        1,
                        0.992593085406,
                        357634.79259981716,
                        357272.55244686815,
                        406178.2267796418)),
                viewing,
                new PreferenceAssessment(
                        local ? "matches" : "not_applicable",
                        List.of(new FilterAssessment(
                                "altitudeDegrees",
                                local ? "matches" : "not_applicable"))),
                local ? outsideForecastHorizon() : null);
    }

    private static Interval interval() {
        return new Interval("2026-09-01T01:00:00Z", "2026-09-01T04:00:00Z");
    }

    private static DisplayInterval display(Interval interval) {
        return new DisplayInterval(
                interval.startsAt(),
                "2026-09-01T02:30:00Z",
                interval.endsAt(),
                new MoonPosition(20.0, 180.0),
                new SunPosition(-15.0, "night"));
    }

    private static MoonPath moonPath(Interval interval, EclipseShadow shadow) {
        return new MoonPath(List.of(
                        pathSample(interval.startsAt(), shadow),
                        pathSample("2026-09-01T02:30:00Z", shadow),
                        pathSample(interval.endsAt(), shadow)));
    }

    private static MoonPathSample pathSample(String at, EclipseShadow shadow) {
        return new MoonPathSample(
                at, 20.0, 180.0, 180.0, 0.0, 12.0,
                -15.0, 182.0, "night", shadow);
    }

    private static Weather outsideForecastHorizon() {
        return new Weather("outside_forecast_horizon", null, null, null, null);
    }
}
