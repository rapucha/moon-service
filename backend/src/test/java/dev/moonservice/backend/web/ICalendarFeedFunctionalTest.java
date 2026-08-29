package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.opportunity.search.OpportunityStatusResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.LocalClockWindow;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimePreference;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.Image;
import net.fortuna.ical4j.validate.ValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ICalendarFeedFunctionalTest {
    private static final String ROUTE = "/calendars/opportunities.ics";
    private static final String LOCATION_ID = "moon-service:3067696";
    private static final String GENERATED_AT = "2026-08-12T12:00:00.987Z";
    private static final String CONTENT_TYPE = "text/calendar;charset=UTF-8";
    private static final String PRIVATE_CACHE = "private, max-age=900";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-24T10:15:30Z"), ZoneOffset.UTC);
    private static final String PREFERENCES = """
            {"version":1,
             "altitudeDegrees":{"minimum":5,"maximum":20,"futureAltitude":true},
             "time":{"mode":"local_clock","window":{"start":"22:15","end":"02:45"}},
             "namedPhases":["full_moon"],
             "future":{"ignored":true}}
            """;

    @Test
    void allOffWeatherAndCanonicalPreferencesUseFixedSoonestSearch() throws Exception {
        String allOff = "all-off";
        String weatherOnly = "weather-only";
        String filtered = "filtered";
        OpportunityPreferences expectedPreferences = new OpportunityPreferences(
                1,
                new AltitudeRange(5, 20),
                null,
                new TimePreference(
                        TimeMode.LOCAL_CLOCK,
                        new LocalClockWindow(LocalTime.of(22, 15), LocalTime.of(2, 45)),
                        null),
                EnumSet.of(NamedPhase.FULL_MOON),
                null);
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq(allOff), eq(Order.SOONEST)))
                .thenReturn(response(allOff, "Europe/Prague", List.of()));
        when(search.search(
                isNull(), eq(weatherOnly), eq(Order.SOONEST),
                eq(WeatherRanking.IGNORE_WEATHER)))
                .thenReturn(response(weatherOnly, "Europe/Prague", List.of()));
        when(search.search(
                isNull(), eq(filtered), eq(Order.SOONEST), eq(expectedPreferences),
                eq(List.of("/altitudeDegrees/futureAltitude", "/future")), eq(2),
                eq(WeatherRanking.PREFER_CLEAR)))
                .thenReturn(response(filtered, "Europe/Prague", List.of()));
        MockMvc mvc = mvc(search);

        mvc.perform(feedGet(allOff)).andExpect(status().isOk());
        mvc.perform(feedGet(weatherOnly).queryParam("weatherRanking", "ignore_weather"))
                .andExpect(status().isOk());
        mvc.perform(feedGet(filtered)
                        .queryParam("weatherRanking", "prefer_clear")
                        .queryParam("preferences", PREFERENCES))
                .andExpect(status().isOk());

        verify(search).search(isNull(), eq(allOff), eq(Order.SOONEST));
        verify(search).search(
                isNull(), eq(weatherOnly), eq(Order.SOONEST),
                eq(WeatherRanking.IGNORE_WEATHER));
        verify(search).search(
                isNull(), eq(filtered), eq(Order.SOONEST), eq(expectedPreferences),
                eq(List.of("/altitudeDegrees/futureAltitude", "/future")), eq(2),
                eq(WeatherRanking.PREFER_CLEAR));
    }

    @Test
    void rejectsOrderDuplicateUnknownMalformedAndInvalidValuesBeforeSearch() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        MockMvc mvc = mvc(search);
        List<MockHttpServletRequestBuilder> invalid = List.of(
                get(ROUTE),
                feedGet(" "),
                feedGet("🌕".repeat(101)),
                feedGet("bad\u0001location"),
                feedGet(LOCATION_ID).queryParam("order", "soonest"),
                get(ROUTE).queryParam("locationId", LOCATION_ID, "duplicate"),
                feedGet(LOCATION_ID).queryParam("unexpected", "value"),
                feedGet(LOCATION_ID).queryParam("preferences", "{not-json"),
                feedGet(LOCATION_ID).queryParam("preferences", "{\"version\":2}"),
                feedGet(LOCATION_ID).queryParam(
                        "preferences",
                        "{\"version\":1,\"altitudeDegrees\":"
                                + "{\"minimum\":91,\"maximum\":92}}"),
                feedGet(LOCATION_ID).queryParam("weatherRanking", "storm_first"),
                head(ROUTE).queryParam("locationId", LOCATION_ID)
                        .queryParam("order", "best_match"));

        for (MockHttpServletRequestBuilder request : invalid) {
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }
        verifyNoInteractions(search);
    }

    @Test
    void mapsMissingLocationAndUnexpectedFailuresToSafeErrors() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq("missing"), eq(Order.SOONEST)))
                .thenReturn(OpportunityStatusResponse.locationNotFound());
        when(search.search(isNull(), eq("boom"), eq(Order.SOONEST)))
                .thenThrow(new IllegalStateException("private-provider-detail"));
        MockMvc mvc = mvc(search);

        expectError(mvc, feedGet("missing"), 404, "location_not_found");
        MvcResult failed = expectError(
                mvc, feedGet("boom"), 503, "temporarily_unavailable");
        assertFalse(failed.getResponse().getContentAsString().contains("private-provider-detail"));

        mvc.perform(head(ROUTE).queryParam("locationId", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void getReturnsPrivateCalendarWithResolvedZoneAndSortedEvents() throws Exception {
        OpportunitySearchResponse.Opportunity late = opportunity(
                "late-event",
                "2026-08-14T03:05:00Z",
                "2026-08-14T03:25:00Z",
                "2026-08-14T03:55:00Z");
        OpportunitySearchResponse.Opportunity tiedSecond = opportunity(
                "b-event",
                "2026-08-14T02:10:00Z",
                "2026-08-14T02:25:00Z",
                "2026-08-14T02:40:00Z");
        OpportunitySearchResponse.Opportunity first = opportunity(
                "a-event",
                "2026-08-14T02:05:30.123Z",
                "2026-08-14T02:25:00Z",
                "2026-08-14T02:55:00.001Z");
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq(LOCATION_ID), eq(Order.SOONEST)))
                .thenReturn(response(
                        LOCATION_ID,
                        "Europe/Prague",
                        List.of(late, tiedSecond, first)));

        MvcResult result = mvc(search).perform(feedGet(LOCATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_CACHE))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertEquals(bytes.length, result.getResponse().getContentLength());
        net.fortuna.ical4j.model.Calendar calendar = parse(bytes);
        ValidationResult validation = calendar.validate();
        assertFalse(validation.hasErrors(), validation.toString());
        List<VTimeZone> timeZones = calendar.getComponents().stream()
                .filter(VTimeZone.class::isInstance)
                .map(VTimeZone.class::cast)
                .toList();
        List<VEvent> events = calendar.getComponents().stream()
                .filter(VEvent.class::isInstance)
                .map(VEvent.class::cast)
                .toList();

        assertEquals(1, timeZones.size());
        assertEquals("Europe/Prague", propertyValue(timeZones.getFirst(), "TZID"));
        assertEquals(
                List.of(uid("a-event"), uid("b-event"), uid("late-event")),
                events.stream().map(event -> propertyValue(event, "UID")).toList());
        VEvent firstEvent = events.getFirst();
        assertEquals("20260814T020500Z", propertyValue(firstEvent, "DTSTART"));
        assertEquals("20260814T025600Z", propertyValue(firstEvent, "DTEND"));
        assertEquals(
                "Moon photography opportunity near Prague",
                propertyValue(firstEvent, "SUMMARY"));
        assertEquals("Prague", propertyValue(firstEvent, "LOCATION"));
        assertEquals(
                String.join("\n",
                        "Suggested local time: 2026-08-14 04:25 Europe/Prague.",
                        "Moon phase: waxing gibbous; illumination: 84.2%; "
                                + "altitude: 5.5 degrees.",
                        "Weather: partly cloudy."),
                propertyValue(firstEvent, "DESCRIPTION"));
        assertMoonImage(firstEvent);
    }

    @Test
    void emptyFeedKeepsResolvedZoneWithoutPlaceholderEvent() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq(LOCATION_ID), eq(Order.SOONEST)))
                .thenReturn(response(LOCATION_ID, "Europe/Prague", List.of()));

        byte[] bytes = mvc(search).perform(feedGet(LOCATION_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        net.fortuna.ical4j.model.Calendar calendar = parse(bytes);

        assertEquals(1, calendar.getComponents().stream()
                .filter(VTimeZone.class::isInstance).count());
        assertEquals(0, calendar.getComponents().stream()
                .filter(VEvent.class::isInstance).count());
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("BEGIN:VEVENT"));
    }

    @Test
    void successiveRendersCapEventsAndRemoveDisappearedUids() throws Exception {
        List<OpportunitySearchResponse.Opportunity> initial = IntStream.range(0, 12)
                .mapToObj(index -> opportunity(
                        "event-" + (index < 10 ? "0" : "") + index,
                        "2026-08-14T02:05:00Z",
                        "2026-08-14T02:25:00Z",
                        "2026-08-14T02:55:00Z"))
                .toList();
        OpportunitySearchResponse.Opportunity survivor = initial.get(5);
        OpportunitySearchResponse.Opportunity replacement = opportunity(
                "event-12",
                "2026-08-14T03:05:00Z",
                "2026-08-14T03:25:00Z",
                "2026-08-14T03:55:00Z");
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq(LOCATION_ID), eq(Order.SOONEST)))
                .thenReturn(
                        response(LOCATION_ID, "Europe/Prague", initial),
                        response(LOCATION_ID, "Europe/Prague", List.of(survivor, replacement)));
        MockMvc mvc = mvc(search);

        List<String> firstUids = eventUids(parse(mvc.perform(feedGet(LOCATION_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()));
        List<String> secondUids = eventUids(parse(mvc.perform(feedGet(LOCATION_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()));

        assertEquals(10, firstUids.size());
        assertEquals(uid("event-05"), firstUids.get(5));
        assertEquals(List.of(firstUids.get(5), uid("event-12")), secondUids);
        assertFalse(secondUids.contains(uid("event-00")));
    }

    @Test
    void headSearchesButSkipsRenderingAndRenderingFailureIsSafe() throws Exception {
        String locationId = "render-only-failure";
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq(locationId), eq(Order.SOONEST)))
                .thenReturn(response(
                        locationId,
                        "Mars/Olympus-private-zone",
                        List.of(opportunity(
                                "render-failure",
                                "2026-08-14T02:05:00Z",
                                "2026-08-14T02:25:00Z",
                                "2026-08-14T02:55:00Z"))));
        MockMvc mvc = mvc(search);

        mvc.perform(head(ROUTE).queryParam("locationId", locationId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_CACHE))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_LENGTH))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED))
                .andExpect(content().bytes(new byte[0]));

        MvcResult failed = expectError(
                mvc, feedGet(locationId), 503, "temporarily_unavailable");
        assertFalse(failed.getResponse().getContentAsString()
                .contains("Mars/Olympus-private-zone"));
        verify(search, times(2)).search(isNull(), eq(locationId), eq(Order.SOONEST));
    }

    private static MockMvc mvc(OpportunitySearchService search) {
        return MockMvcBuilders.standaloneSetup(
                new ICalendarFeedController(search, CLOCK)).build();
    }

    private static MockHttpServletRequestBuilder feedGet(String locationId) {
        return get(ROUTE).queryParam("locationId", locationId);
    }

    private static MvcResult expectError(
            MockMvc mvc,
            MockHttpServletRequestBuilder request,
            int httpStatus,
            String responseStatus
    ) throws Exception {
        return mvc.perform(request)
                .andExpect(status().is(httpStatus))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(responseStatus))
                .andReturn();
    }

    private static net.fortuna.ical4j.model.Calendar parse(byte[] bytes) throws Exception {
        return new CalendarBuilder().build(new ByteArrayInputStream(bytes));
    }

    private static String propertyValue(CalendarComponent component, String name) {
        return component.getPropertyList().getAll().stream()
                .filter(property -> name.equals(property.getName()))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private static List<String> eventUids(net.fortuna.ical4j.model.Calendar calendar) {
        return calendar.getComponents().stream()
                .filter(VEvent.class::isInstance)
                .map(VEvent.class::cast)
                .map(event -> propertyValue(event, "UID"))
                .toList();
    }

    private static void assertMoonImage(VEvent event) throws Exception {
        List<Property> images = event.getPropertyList().getAll().stream()
                .filter(property -> Image.PROPERTY_NAME.equals(property.getName()))
                .toList();
        assertEquals(1, images.size());
        Image image = (Image) images.getFirst();
        assertEquals("BASE64", image.getRequiredParameter(Parameter.ENCODING).getValue());
        assertEquals("BINARY", image.getRequiredParameter(Parameter.VALUE).getValue());
        assertEquals("BADGE", image.getRequiredParameter(Parameter.DISPLAY).getValue());
        assertEquals("image/png", image.getRequiredParameter(Parameter.FMTTYPE).getValue());
        BufferedImage decoded = ImageIO.read(
                new ByteArrayInputStream(Base64.getDecoder().decode(image.getValue())));
        assertNotNull(decoded);
        assertEquals(192, decoded.getWidth());
        assertEquals(192, decoded.getHeight());
    }

    private static String uid(String opportunityId) {
        return "urn:uuid:" + UUID.nameUUIDFromBytes(
                ("moon-service.ics.event.v1\n" + LOCATION_ID + "\n" + opportunityId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static OpportunitySearchResponse response(
            String locationId,
            String timezone,
            List<OpportunitySearchResponse.Opportunity> opportunities
    ) {
        return new OpportunitySearchResponse(
                "ok",
                GENERATED_AT,
                new OpportunitySearchResponse.Location(
                        locationId,
                        "real_location",
                        "Prague",
                        50.08804,
                        14.42076,
                        202,
                        timezone,
                        "CZ"),
                7,
                "2026-08-12T00:00:00Z",
                "2026-08-19T00:00:00Z",
                opportunities.size(),
                90.0,
                opportunities,
                List.of(),
                List.of());
    }

    private static OpportunitySearchResponse.Opportunity opportunity(
            String id,
            String startsAt,
            String suggestedAt,
            String endsAt
    ) {
        return new OpportunitySearchResponse.Opportunity(
                id,
                "moonrise_low",
                null,
                startsAt,
                suggestedAt,
                endsAt,
                "Europe/Prague",
                83,
                "high",
                null,
                null,
                new OpportunitySearchResponse.Moon(
                        5.5, 91.0, 84.2, 162.0, 27.4, -13.6, "waxing_gibbous"),
                null,
                new OpportunitySearchResponse.Sun(-5.0, 72.0, "civil_twilight"),
                new OpportunitySearchResponse.Weather(
                        "hourly", "partly_cloudy", 63, 71, 30, 44, 51,
                        27, 0.4, 10_000, 2, "partly cloudy"),
                null,
                "Private ranking reason.",
                Map.of());
    }
}
