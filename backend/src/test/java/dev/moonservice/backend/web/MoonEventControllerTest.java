package dev.moonservice.backend.web;

import dev.moonservice.backend.events.LunarEclipseEventService;
import dev.moonservice.backend.events.MoonEventResponse;
import dev.moonservice.backend.events.MoonEventResponse.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    void returnsTheClosedEventShapeAndOnlyAggregateIgnoredFieldLogging(CapturedOutput output)
            throws Exception {
        LunarEclipseEventService service = mock(LunarEclipseEventService.class);
        when(service.search(anyString(), any(), anyList(), anyInt()))
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
                "umbralObscurationPercent", "phases", "moonAtMaximum",
                "localVisibility", "preferenceAssessment", "weather");
        assertThat(response.at("/events/0/phases/0/localVisibility").propertyNames())
                .containsExactlyInAnyOrder("status", "intervals");
        assertThat(response.at("/events/0/localVisibility").propertyNames())
                .containsExactlyInAnyOrder("status", "intervals", "selectedInterval", "displayInterval");
        assertThat(response.at("/events/0/weather").propertyNames()).containsExactlyInAnyOrder(
                "status", "forecastHourStartsAt", "summary", "cloudCoverPercent",
                "precipitationProbabilityPercent");
        assertThat(response.at("/events/1/weather").propertyNames()).containsExactly("status");

        verify(service).search(
                eq("prague-cz"),
                argThat(preferences -> preferences.normalizedFilters().keySet()
                        .equals(Set.of("altitudeDegrees"))),
                eq(List.of("/private-marker")),
                eq(1));
        assertThat(output.getOut()).contains(
                "ignored_preference_fields preferenceVersion=1 count=1 truncated=false");
        assertThat(output.getOut()).doesNotContain("secret-value", "private-marker", "prague-cz");
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidBodiesAndQueryVariantsBeforeServiceWork(String uri, String body, String message)
            throws Exception {
        LunarEclipseEventService service = mock(LunarEclipseEventService.class);

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
        LunarEclipseEventService service = mock(LunarEclipseEventService.class);
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
        LunarEclipseEventService service = mock(LunarEclipseEventService.class);
        when(service.search(anyString(), any(), anyList(), anyInt()))
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

    private static MockMvc mvc(LunarEclipseEventService service) {
        return MockMvcBuilders.standaloneSetup(new MoonEventController(service))
                .setControllerAdvice(new OpportunitySearchErrorHandler())
                .addFilters(new HostedAlphaSurfaceFilter(false))
                .build();
    }

    private static Success successResponse() {
        LunarEclipseEvent available = event("event-1", new Weather(
                "available", "2026-09-01T02:00:00Z", "partly cloudy", 38, 5));
        LunarEclipseEvent outside = event("event-2", new Weather(
                "outside_forecast_horizon", null, null, null, null));
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
                List.of(available, outside));
    }

    private static LunarEclipseEvent event(String id, Weather weather) {
        Interval interval = new Interval("2026-09-01T01:00:00Z", "2026-09-01T04:00:00Z");
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
                new MoonPosition(20.0, 180.0),
                new EventVisibility(
                        "fully_visible",
                        List.of(interval),
                        interval,
                        new DisplayInterval(
                                interval.startsAt(),
                                "2026-09-01T02:30:00Z",
                                interval.endsAt(),
                                new MoonPosition(20.0, 180.0),
                                new SunPosition(-15.0, "night"))),
                new PreferenceAssessment(
                        "matches", List.of(new FilterAssessment("altitudeDegrees", "matches"))),
                weather);
    }
}
