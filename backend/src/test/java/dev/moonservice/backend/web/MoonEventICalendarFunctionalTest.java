package dev.moonservice.backend.web;

import dev.moonservice.backend.events.MoonEventResponse;
import dev.moonservice.backend.events.MoonEventResponse.*;
import dev.moonservice.backend.events.MoonEventService;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.validate.ValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MoonEventICalendarFunctionalTest {
    private static final String LOCATION_ID = "moon-service:3067696";
    private static final String GENERATED_AT = "2026-08-30T10:00:00Z";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse(GENERATED_AT), ZoneOffset.UTC);

    @Test
    void getRendersTheSelectedEclipseIntervalAndCompletePlainCalendar() throws Exception {
        MoonEventService service = mock(MoonEventService.class);
        when(service.search(eq(LOCATION_ID), any(), eq(36), anyList(), anyInt()))
                .thenReturn(success(eclipse("eclipse-1")));

        MvcResult result = mvc(service).perform(calendarGet("eclipse-1")
                        .param("preferences", """
                                {"version":1,
                                 "altitudeDegrees":{"minimum":10,"maximum":30},
                                 "azimuthDegrees":{"included":{"start":90,"end":220}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_TYPE, "text/calendar;charset=UTF-8"))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"moon-event.ics\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        byte[] content = result.getResponse().getContentAsByteArray();
        assertEquals(content.length, result.getResponse().getContentLength());
        VEvent event = calendarEvent(content);
        assertEquals("20260901T011000Z", propertyValue(event, "DTSTART"));
        assertEquals("20260901T035000Z", propertyValue(event, "DTEND"));
        assertEquals(
                "total lunar eclipse near Prague, Czechia",
                propertyValue(event, "SUMMARY"));
        assertEquals("Prague, Czechia", propertyValue(event, "LOCATION"));
        assertEquals(String.join("\n",
                        "Location: Prague, Czechia.",
                        "Event: total lunar eclipse.",
                        "Eclipse maximum: 2026-09-01 04:30 Europe/Prague.",
                        "Suggested local time: 2026-09-01 04:20 Europe/Prague; "
                                + "Moon altitude: 21.3 degrees; azimuth: 185.4 degrees.",
                        "Calendar block: 2026-09-01 03:10 Europe/Prague "
                                + "to 2026-09-01 05:50 Europe/Prague.",
                        "Altitude preference: does not match.",
                        "Direction preference: matches.",
                        "Weather: partly cloudy.",
                        "Visibility uses a level horizon; terrain, buildings, "
                                + "and local obstructions are not included."),
                propertyValue(event, "DESCRIPTION"));
        String serialized = new String(content, StandardCharsets.UTF_8);
        assertFalse(serialized.contains("\r\nIMAGE"));
        assertFalse(serialized.contains("\r\nATTACH"));
        assertFalse(serialized.contains("BEGIN:VALARM"));
        verify(service).search(
                eq(LOCATION_ID),
                argThat(preferences -> preferences.normalizedFilters().keySet()
                        .equals(Set.of("altitudeDegrees", "azimuthDegrees"))),
                eq(36),
                eq(List.of()),
                eq(0));
    }

    @Test
    void fullMoonTimingCoversClippingNoLocalViewingAndHead() throws Exception {
        MoonEventService service = mock(MoonEventService.class);
        when(service.search(eq(LOCATION_ID), any(), eq(36), anyList(), anyInt()))
                .thenReturn(success(
                        visibleFullMoon("visible-full-moon"),
                        notVisibleFullMoon("not-visible-full-moon")));
        MockMvc mvc = mvc(service);

        MvcResult visible = mvc.perform(calendarGet("visible-full-moon"))
                .andExpect(status().isOk())
                .andReturn();
        VEvent visibleEvent = calendarEvent(visible.getResponse().getContentAsByteArray());
        assertEquals("20270122T120500Z", propertyValue(visibleEvent, "DTSTART"));
        assertEquals("20270122T123500Z", propertyValue(visibleEvent, "DTEND"));
        assertTrue(propertyValue(visibleEvent, "DESCRIPTION")
                .contains("Suggested local time: 2027-01-22 13:20 Europe/Prague"));

        VEvent notVisible = calendarEvent(mvc.perform(calendarGet("not-visible-full-moon"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals("20270122T143000Z", propertyValue(notVisible, "DTSTART"));
        assertEquals("20270122T153000Z", propertyValue(notVisible, "DTEND"));
        assertTrue(propertyValue(notVisible, "DESCRIPTION")
                .contains("Not visible from Prague, Czechia."));

        mvc.perform(head("/events/{id}.ics", "visible-full-moon")
                        .param("locationId", LOCATION_ID)
                        .param("eventHorizonMonths", "36"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_TYPE,
                        visible.getResponse().getHeader(HttpHeaders.CONTENT_TYPE)))
                .andExpect(header().longValue(
                        HttpHeaders.CONTENT_LENGTH,
                        visible.getResponse().getContentAsByteArray().length))
                .andExpect(content().bytes(new byte[0]));
        mvc.perform(post("/events/{id}.ics", "visible-full-moon"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "GET, HEAD"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void rejectsInvalidQueriesAndMapsStaleAndServiceFailuresSafely() throws Exception {
        MoonEventService service = mock(MoonEventService.class);
        MockMvc mvc = mvc(service);

        expectError(mvc, get("/events/event.ics").param("locationId", LOCATION_ID),
                400, "invalid_request");
        verifyNoInteractions(service);

        when(service.search(eq("stale"), any(), eq(36), anyList(), anyInt()))
                .thenReturn(success(eclipse("different-event")));
        MvcResult stale = expectError(mvc, calendarGet("missing-event", "stale"),
                404, "event_not_found");
        assertTrue(stale.getResponse().getContentAsString()
                .contains("Refresh the Moon Service search"));

        when(service.search(eq("missing"), any(), eq(36), anyList(), anyInt()))
                .thenReturn(new MoonEventResponse.Status(
                        "location_not_found", GENERATED_AT, "No matching location found."));
        expectError(mvc, calendarGet("event", "missing"),
                404, "location_not_found");

        when(service.search(eq("boom"), any(), eq(36), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("private provider detail"));
        MvcResult failed = expectError(mvc, calendarGet("event", "boom"),
                503, "temporarily_unavailable");
        assertFalse(failed.getResponse().getContentAsString().contains("private provider detail"));

        mvc.perform(head("/events/{id}.ics", "missing-event")
                        .param("locationId", "stale")
                        .param("eventHorizonMonths", "36"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().bytes(new byte[0]));
    }

    private static MockMvc mvc(MoonEventService service) {
        ICalendarEventController controller = new ICalendarEventController(
                mock(OpportunitySearchService.class), CLOCK);
        controller.setMoonEventService(service);
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new HostedAlphaSurfaceFilter(true))
                .build();
    }

    private static MockHttpServletRequestBuilder calendarGet(String eventId) {
        return calendarGet(eventId, LOCATION_ID);
    }

    private static MockHttpServletRequestBuilder calendarGet(
            String eventId,
            String locationId
    ) {
        return get("/events/{id}.ics", eventId)
                .param("locationId", locationId)
                .param("eventHorizonMonths", "36");
    }

    private static MvcResult expectError(
            MockMvc mvc,
            MockHttpServletRequestBuilder request,
            int statusCode,
            String responseStatus
    ) throws Exception {
        return mvc.perform(request)
                .andExpect(status().is(statusCode))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(responseStatus))
                .andReturn();
    }

    private static VEvent calendarEvent(byte[] content) throws Exception {
        net.fortuna.ical4j.model.Calendar calendar =
                new CalendarBuilder().build(new ByteArrayInputStream(content));
        ValidationResult validation = calendar.validate();
        assertFalse(validation.hasErrors(), validation.toString());
        assertEquals(1, calendar.getComponents().size());
        return (VEvent) calendar.getComponents().getFirst();
    }

    private static String propertyValue(CalendarComponent component, String name) {
        return component.getPropertyList().getAll().stream()
                .filter(property -> name.equals(property.getName()))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private static Success success(MoonEvent... events) {
        return new Success(
                "ok",
                GENERATED_AT,
                GENERATED_AT,
                "2029-08-30T10:00:00Z",
                new MoonEventResponse.Location(
                        LOCATION_ID,
                        "real_location",
                        "Prague, Czechia",
                        "Europe/Prague",
                        "CZ"),
                1,
                Map.of(),
                List.of(),
                0,
                0,
                List.of(events));
    }

    private static LunarEclipseEvent eclipse(String id) {
        Interval selected = new Interval(
                "2026-09-01T01:10:00Z", "2026-09-01T03:50:00Z");
        DisplayInterval display = new DisplayInterval(
                "2026-09-01T01:45:00Z",
                "2026-09-01T02:20:00Z",
                "2026-09-01T03:00:00Z",
                new MoonPosition(21.3, 185.4),
                new SunPosition(-18.0, "night"));
        return new LunarEclipseEvent(
                id,
                "lunar_eclipse",
                "total",
                "2026-09-01T00:30:00Z",
                "2026-09-01T02:30:00Z",
                "2026-09-01T04:30:00Z",
                100.0,
                List.of(),
                List.of(),
                new MoonPosition(22.0, 180.0),
                new EventVisibility(
                        "fully_visible",
                        List.of(selected),
                        selected,
                        display,
                        new MoonPath(List.of())),
                new PreferenceAssessment("does_not_match", List.of(
                        new FilterAssessment("altitudeDegrees", "does_not_match"),
                        new FilterAssessment("azimuthDegrees", "matches"))),
                new Weather(
                        "available",
                        "2026-09-01T02:00:00Z",
                        "partly cloudy",
                        38,
                        5));
    }

    private static FullMoonEvent visibleFullMoon(String id) {
        Interval selected = new Interval(
                "2027-01-22T12:05:00Z", "2027-01-22T12:35:00Z");
        return fullMoon(
                id,
                "2027-01-22T12:17:00Z",
                new LocalViewing(
                        List.of(selected),
                        selected,
                        new DisplayInterval(
                                selected.startsAt(),
                                "2027-01-22T12:20:00Z",
                                selected.endsAt(),
                                new MoonPosition(17.5, 112.2),
                                new SunPosition(-10.0, "night")),
                        new MoonPath(List.of())),
                new Weather(
                        "outside_forecast_horizon", null, null, null, null));
    }

    private static FullMoonEvent notVisibleFullMoon(String id) {
        return fullMoon(id, "2027-01-22T15:00:00Z", null, null);
    }

    private static FullMoonEvent fullMoon(
            String id,
            String peakAt,
            LocalViewing localViewing,
            Weather weather
    ) {
        return new FullMoonEvent(
                id,
                "full_moon",
                peakAt,
                List.of(new FullMoonQualifier(
                        "near_perigee", 1, 0.99, 357_635.0, 357_273.0, 406_178.0)),
                localViewing,
                new PreferenceAssessment(
                        localViewing == null ? "not_applicable" : "matches",
                        List.of(new FilterAssessment(
                                "altitudeDegrees",
                                localViewing == null ? "not_applicable" : "matches"))),
                weather);
    }
}
