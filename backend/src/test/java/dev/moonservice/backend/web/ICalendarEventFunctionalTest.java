package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.LocationCandidatesResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.opportunity.search.OpportunityStatusResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AzimuthPreference;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.LocalClockWindow;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimePreference;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Image;
import net.fortuna.ical4j.validate.ValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ICalendarEventFunctionalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LOCATION_ID = "moon-service:3067696";
    private static final String OPPORTUNITY_ID = "prague-20260814T022500Z";
    private static final String GENERATED_AT = "2026-08-12T12:00:00.987Z";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-24T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void productGetAndPreferencePostReturnCompleteCanonicalLinks() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        OpportunitySearchResponse source = response(
                LOCATION_ID, "Prague", standardOpportunity(OPPORTUNITY_ID));
        when(search.search(isNull(), eq("lookup-alias"), eq(Order.BEST_MATCH))).thenReturn(source);
        when(search.search(
                eq("Prague"),
                isNull(),
                eq(Order.SOONEST),
                any(OpportunityPreferences.class),
                eq(List.of()),
                eq(0),
                eq(WeatherRanking.PREFER_CLEAR))).thenReturn(source);
        MockMvc mvc = mvc(search);

        MvcResult getResult = mvc.perform(get("/api/opportunities")
                        .param("locationId", "lookup-alias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opportunities[0].links.ics").value(
                        "/o/" + OPPORTUNITY_ID + ".ics?locationId=moon-service%3A3067696"))
                .andReturn();
        assertOnlyCalendarLinksChanged(source, getResult);

        String body = """
                {"q":"Prague","weatherRanking":"prefer_clear","preferences":{
                  "version":1,"altitudeDegrees":{"minimum":5,"maximum":20.5},
                  "namedPhases":["full_moon"]}}
                """;
        MvcResult postResult = mvc.perform(post("/api/opportunities")
                        .queryParam("order", "soonest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        String link = MAPPER.readTree(postResult.getResponse().getContentAsByteArray())
                .path("opportunities").get(0).path("links").path("ics").asString();
        assertEquals("/o/" + OPPORTUNITY_ID + ".ics?locationId=moon-service%3A3067696"
                + "&order=soonest&weatherRanking=prefer_clear"
                + "&preferences=%7B%22version%22%3A1%2C%22altitudeDegrees%22%3A%7B"
                + "%22minimum%22%3A5%2C%22maximum%22%3A20.5%7D%2C%22namedPhases%22%3A"
                + "%5B%22full_moon%22%5D%7D", link);
        assertEquals(ProductWeatherRanking.PREFER_CLEAR,
                PublicPreferenceQuery.parseCalendar(requestFromLink(link)).weatherRanking());
        assertOnlyCalendarLinksChanged(source, postResult);
    }

    @Test
    void generatedPreferenceLinkRoundTripsThroughTheExistingSearchOverload() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        OpportunitySearchResponse source = response(
                LOCATION_ID, "Prague", standardOpportunity(OPPORTUNITY_ID));
        when(search.search(
                eq("Prague"), isNull(), eq(Order.SOONEST), any(OpportunityPreferences.class),
                anyList(), anyInt(), eq(WeatherRanking.IGNORE_WEATHER))).thenReturn(source);
        when(search.search(
                isNull(), eq(LOCATION_ID), eq(Order.SOONEST), any(OpportunityPreferences.class),
                anyList(), anyInt(), eq(WeatherRanking.IGNORE_WEATHER))).thenReturn(source);
        MockMvc mvc = mvc(search);

        MvcResult product = mvc.perform(post("/api/opportunities")
                        .queryParam("order", "soonest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"Prague","weatherRanking":"ignore_weather","preferences":{
                                  "version":1,"time":{"mode":"light_bucket",
                                  "buckets":["night","civil_twilight"]},"future":true}}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String link = MAPPER.readTree(product.getResponse().getContentAsByteArray())
                .path("opportunities").get(0).path("links").path("ics").asString();
        assertFalse(link.contains("future"));

        mvc.perform(get(URI.create(link)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/calendar;charset=UTF-8"));
        verify(search).search(
                isNull(), eq(LOCATION_ID), eq(Order.SOONEST), any(OpportunityPreferences.class),
                eq(List.of()), eq(0), eq(WeatherRanking.IGNORE_WEATHER));
    }

    @Test
    void canonicalCodecCoversEveryVersionOneFieldAndNormalizesSetValues() {
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                new AltitudeRange(5.0, 20.50),
                new AzimuthPreference(new DegreeRange(350, 20), new DegreeRange(355, 5)),
                new TimePreference(
                        TimeMode.LIGHT_BUCKET,
                        null,
                        EnumSet.of(AmbientLight.NIGHT, AmbientLight.DAYLIGHT)),
                EnumSet.of(NamedPhase.FULL_MOON, NamedPhase.NEW_MOON),
                List.of(
                        new DegreeRange(20, 30),
                        new DegreeRange(-0.0, 10),
                        new DegreeRange(20.0, 30.0)));

        String link = PublicPreferenceQuery.calendarQuery(
                "Praha / Žižkov", Order.SOONEST, ProductWeatherRanking.IGNORE_WEATHER, preferences);
        assertTrue(link.startsWith("?locationId=Praha%20%2F%20%C5%BDi%C5%BEkov"
                + "&order=soonest&weatherRanking=ignore_weather&preferences="));
        assertEquals("{" +
                        "\"version\":1," +
                        "\"altitudeDegrees\":{\"minimum\":5,\"maximum\":20.5}," +
                        "\"azimuthDegrees\":{\"included\":{\"start\":350,\"end\":20},"
                        + "\"excluded\":{\"start\":355,\"end\":5}}," +
                        "\"time\":{\"mode\":\"light_bucket\","
                        + "\"buckets\":[\"daylight\",\"night\"]}," +
                        "\"namedPhases\":[\"new_moon\",\"full_moon\"]," +
                        "\"brightLimbOrientationDegrees\":[{\"start\":0,\"end\":10},"
                        + "{\"start\":20,\"end\":30}]}"
                , decodedQueryValue(link, "preferences"));

        MockHttpServletRequest request = requestFromLink(link);
        PublicPreferenceQuery.CalendarRequest parsed = PublicPreferenceQuery.parseCalendar(request);
        assertEquals(Order.SOONEST, parsed.order());
        assertEquals(ProductWeatherRanking.IGNORE_WEATHER, parsed.weatherRanking());
        assertEquals(2, parsed.preferences().brightLimbOrientationDegrees().size());
        assertEquals(Set.of(AmbientLight.DAYLIGHT, AmbientLight.NIGHT),
                parsed.preferences().time().lightBuckets());
    }

    @Test
    void canonicalCodecHandlesClockWindowsGoldenValueAndDefaultOmission() {
        OpportunityPreferences altitude = new OpportunityPreferences(
                1, new AltitudeRange(5, 20.5), null, null, null, null);
        assertEquals("?locationId=location&preferences="
                        + "%7B%22version%22%3A1%2C%22altitudeDegrees%22%3A%7B"
                        + "%22minimum%22%3A5%2C%22maximum%22%3A20.5%7D%7D",
                PublicPreferenceQuery.calendarQuery(
                        "location", Order.BEST_MATCH, ProductWeatherRanking.BALANCED, altitude));

        OpportunityPreferences clock = new OpportunityPreferences(
                1,
                null,
                null,
                new TimePreference(
                        TimeMode.LOCAL_CLOCK,
                        new LocalClockWindow(LocalTime.of(22, 15), LocalTime.of(2, 45)),
                        null),
                null,
                null);
        String query = PublicPreferenceQuery.calendarQuery(
                "location", Order.BEST_MATCH, null, clock);
        assertEquals("{\"version\":1,\"time\":{\"mode\":\"local_clock\","
                        + "\"window\":{\"start\":\"22:15\",\"end\":\"02:45\"}}}",
                decodedQueryValue(query, "preferences"));
        assertEquals("?locationId=location", PublicPreferenceQuery.calendarQuery(
                "location", Order.BEST_MATCH, null, OpportunityPreferences.none()));
        PublicPreferenceQuery.CalendarRequest defaults = PublicPreferenceQuery.parseCalendar(
                requestFromLink("?locationId=location"));
        assertEquals(Order.BEST_MATCH, defaults.order());
        assertEquals(null, defaults.weatherRanking());
    }

    @Test
    void returnsExactEscapedFoldedCalendarBytesAndOutwardMinuteBounds() throws Exception {
        String unicodeTail = "Ž".repeat(40) + "🌕".repeat(20);
        String displayName = "Praha, Žižkov; observatoř\\sever\rNorth " + unicodeTail;
        String escapedDisplayName =
                "Praha\\, Žižkov\\; observatoř\\\\sever\\nNorth " + unicodeTail;
        OpportunitySearchResponse.Opportunity opportunity = opportunity(
                OPPORTUNITY_ID,
                "2026-08-14T02:05:30.123Z",
                "2026-08-14T02:25:45Z",
                "2026-08-14T02:55:00.001Z",
                "mostly clear, calm; dry\\wind\rlater");
        OpportunitySearchResponse response = response(LOCATION_ID, displayName, opportunity);
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq(LOCATION_ID), eq(Order.BEST_MATCH))).thenReturn(response);

        MvcResult result = mvc(search).perform(get("/o/{id}.ics", OPPORTUNITY_ID)
                        .param("locationId", LOCATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/calendar;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"moon-opportunity.ics\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertEquals(bytes.length, result.getResponse().getContentLength());
        String text = strictUtf8(bytes);
        assertPhysicalLines(text);
        net.fortuna.ical4j.model.Calendar parsed =
                new CalendarBuilder().build(new ByteArrayInputStream(bytes));
        ValidationResult parsedValidation = parsed.validate();
        assertFalse(parsedValidation.hasErrors(), parsedValidation.toString());
        assertEquals(1, parsed.getComponents().size());
        VEvent event = (VEvent) parsed.getComponents().getFirst();
        List<Property> imageProperties = event.getPropertyList().getAll().stream()
                .filter(property -> Image.PROPERTY_NAME.equals(property.getName()))
                .toList();
        assertEquals(1, imageProperties.size());
        Image image = (Image) imageProperties.getFirst();
        assertEquals(
                List.of(Parameter.ENCODING, Parameter.VALUE, Parameter.DISPLAY, Parameter.FMTTYPE),
                image.getParameterList().getAll().stream().map(Parameter::getName).toList());
        assertEquals("BASE64", image.getRequiredParameter(Parameter.ENCODING).getValue());
        assertEquals("BINARY", image.getRequiredParameter(Parameter.VALUE).getValue());
        assertEquals("BADGE", image.getRequiredParameter(Parameter.DISPLAY).getValue());
        assertEquals("image/png", image.getRequiredParameter(Parameter.FMTTYPE).getValue());
        assertFalse(image.getValue().startsWith("data:"));
        BufferedImage actualMoon = ImageIO.read(
                new ByteArrayInputStream(Base64.getDecoder().decode(image.getValue())));
        assertNotNull(actualMoon);
        assertEquals(192, actualMoon.getWidth());
        assertEquals(192, actualMoon.getHeight());
        assertEquals(0, actualMoon.getRGB(0, 0) >>> 24);
        assertEquals(255, actualMoon.getRGB(96, 96) >>> 24);
        assertNotNull(opportunity.moon().brightLimbTiltDegrees());
        assertNotNull(opportunity.moon().northPoleTiltDegrees());
        BufferedImage expectedMoon = new BufferedImage(192, 192, BufferedImage.TYPE_INT_ARGB);
        AtomMoonRenderer.draw(
                expectedMoon, 96, 96, 88, AtomMoonRenderer.MoonStyle.from(opportunity.moon()));
        assertArrayEquals(
                expectedMoon.getRGB(0, 0, 192, 192, null, 0, 192),
                actualMoon.getRGB(0, 0, 192, 192, null, 0, 192));
        String unfolded = replaceImagePayload(text.replace("\r\n ", ""));
        String uid = "urn:uuid:" + UUID.nameUUIDFromBytes(
                ("moon-service.ics.event.v1\n" + LOCATION_ID + "\n" + OPPORTUNITY_ID)
                        .getBytes(StandardCharsets.UTF_8));
        String expected = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
                + "PRODID:-//Moon Service//Moon Opportunity//EN\r\nCALSCALE:GREGORIAN\r\n"
                + "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20260812T120000Z\r\n"
                + "DTSTART:20260814T020500Z\r\nDTEND:20260814T025600Z\r\n"
                + "SUMMARY:Moon photography opportunity near " + escapedDisplayName + "\r\n"
                + "LOCATION:" + escapedDisplayName + "\r\n"
                + "DESCRIPTION:Suggested local time: 2026-08-14 04:25 Europe/Prague.\\n"
                + "Moon phase: waxing gibbous\\; illumination: 84.2%\\; altitude: 5.5 degrees.\\n"
                + "Weather: mostly clear\\, calm\\; dry\\\\wind\\nlater.\r\n"
                + "IMAGE;ENCODING=BASE64;VALUE=BINARY;DISPLAY=BADGE;FMTTYPE=image/png:<png>\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
        assertEquals(expected, unfolded);
        assertFalse(unfolded.contains("50.08804"));
        assertFalse(unfolded.contains("prefer_clear"));
    }

    @Test
    void headReturnsTheGetHeadersWithoutABody() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq(LOCATION_ID), eq(Order.BEST_MATCH)))
                .thenReturn(response(LOCATION_ID, "Prague", standardOpportunity(OPPORTUNITY_ID)));
        MockMvc mvc = mvc(search);
        MvcResult get = mvc.perform(get("/o/{id}.ics", OPPORTUNITY_ID)
                        .param("locationId", LOCATION_ID))
                .andReturn();
        String getContentType = Objects.requireNonNull(
                get.getResponse().getHeader(HttpHeaders.CONTENT_TYPE));

        mvc.perform(head("/o/{id}.ics", OPPORTUNITY_ID).param("locationId", LOCATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, getContentType))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH,
                        get.getResponse().getContentAsByteArray().length))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void rejectsMalformedPathAndQueryInputsWithoutSearching() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        MockMvc mvc = mvc(search);

        expectError(mvc, get("/o/.ics").param("locationId", LOCATION_ID), 400, "invalid_request");
        expectError(mvc, get("/o/{id}.ics", OPPORTUNITY_ID), 400, "invalid_request");
        for (String locationId : List.of(
                " ", "x".repeat(101), "bad\u0001location", "\t" + LOCATION_ID,
                " ".repeat(101) + LOCATION_ID)) {
            expectError(mvc, get("/o/{id}.ics", OPPORTUNITY_ID)
                    .param("locationId", locationId), 400, "invalid_request");
        }
        expectError(mvc, get("/o/{id}.ics", OPPORTUNITY_ID)
                .param("locationId", LOCATION_ID).param("unknown", "value"), 400, "invalid_request");
        expectError(mvc, get("/o/{id}.ics", OPPORTUNITY_ID)
                .param("locationId", LOCATION_ID, LOCATION_ID), 400, "invalid_request");
        expectError(mvc, get("/o/{id}.ics", OPPORTUNITY_ID)
                .param("locationId", LOCATION_ID).param("preferences", "{"), 400, "invalid_request");
        expectError(mvc, get("/o/{id}.ics", OPPORTUNITY_ID)
                .param("locationId", LOCATION_ID)
                .param("preferences", "{\"version\":2}"), 400, "invalid_request");
        expectError(mvc, get("/o/{id}.ics", OPPORTUNITY_ID)
                .param("locationId", LOCATION_ID)
                .param("preferences", "{\"version\":1,\"altitudeDegrees\":{"
                        + "\"minimum\":30,\"maximum\":20}}"), 400, "invalid_request");
        verifyNoInteractions(search);

        MockHttpServletRequest directRequest = new MockHttpServletRequest("GET", "/o/id.ics");
        directRequest.addParameter("locationId", LOCATION_ID);
        ICalendarEventController controller = new ICalendarEventController(search, CLOCK);
        for (String opportunityId : List.of("x".repeat(201), "bad\u0001id")) {
            assertEquals(HttpStatus.BAD_REQUEST,
                    controller.event(opportunityId, directRequest).getStatusCode());
        }
        verifyNoInteractions(search);
    }

    @Test
    void mapsLocationStaleProviderAndUnexpectedFailuresToSafeErrors() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq("missing"), eq(Order.BEST_MATCH)))
                .thenReturn(OpportunityStatusResponse.locationNotFound());
        when(search.search(isNull(), eq("stale"), eq(Order.BEST_MATCH)))
                .thenReturn(response("stale", "Prague", standardOpportunity("different-id")));
        when(search.search(isNull(), eq("unavailable"), eq(Order.BEST_MATCH)))
                .thenReturn(OpportunityStatusResponse.temporarilyUnavailable());
        when(search.search(isNull(), eq("ambiguous"), eq(Order.BEST_MATCH)))
                .thenReturn(new LocationCandidatesResponse("ambiguous_location", GENERATED_AT, List.of()));
        when(search.search(isNull(), eq("boom"), eq(Order.BEST_MATCH)))
                .thenThrow(new IllegalStateException("private provider detail"));
        when(search.search(isNull(), eq("bad-location"), eq(Order.BEST_MATCH)))
                .thenThrow(new InvalidOpportunitySearchRequestException("locationId is invalid."));
        MockMvc mvc = mvc(search);

        expectError(mvc, calendarGet("missing"), 404, "location_not_found");
        MvcResult stale = expectError(mvc, calendarGet("stale"), 404, "opportunity_not_found");
        assertTrue(stale.getResponse().getContentAsString().contains("Refresh the Moon Service search"));
        expectError(mvc, calendarGet("unavailable"), 503, "temporarily_unavailable");
        expectError(mvc, calendarGet("ambiguous"), 503, "temporarily_unavailable");
        MvcResult boom = expectError(mvc, calendarGet("boom"), 503, "temporarily_unavailable");
        assertFalse(boom.getResponse().getContentAsString().contains("private provider detail"));
        expectError(mvc, calendarGet("bad-location"), 400, "invalid_request");

        mvc.perform(head("/o/{id}.ics", OPPORTUNITY_ID).param("locationId", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().bytes(new byte[0]));
    }

    private static MockMvc mvc(OpportunitySearchService search) {
        return MockMvcBuilders.standaloneSetup(
                new OpportunitySearchController(search),
                new ICalendarEventController(search, CLOCK)).build();
    }

    private static MockHttpServletRequestBuilder calendarGet(String locationId) {
        return get("/o/{id}.ics", OPPORTUNITY_ID).param("locationId", locationId);
    }

    private static MvcResult expectError(
            MockMvc mvc,
            MockHttpServletRequestBuilder request,
            int httpStatus,
            String statusCode
    ) throws Exception {
        return mvc.perform(request)
                .andExpect(status().is(httpStatus))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(jsonPath("$.status").value(statusCode))
                .andReturn();
    }

    private static void assertOnlyCalendarLinksChanged(
            OpportunitySearchResponse source,
            MvcResult result
    ) throws Exception {
        JsonNode before = MAPPER.valueToTree(source);
        JsonNode after = MAPPER.readTree(result.getResponse().getContentAsByteArray());
        ((tools.jackson.databind.node.ObjectNode) before.path("opportunities").get(0)).remove("links");
        ((tools.jackson.databind.node.ObjectNode) after.path("opportunities").get(0)).remove("links");
        assertEquals(before, after);
        assertNotSame(source.opportunities().get(0).links(),
                ((OpportunitySearchResponse) OpportunityCalendarLinkAssembler.withCalendarLinks(
                        source, Order.BEST_MATCH, null, null)).opportunities().get(0).links());
    }

    private static String strictUtf8(byte[] calendar) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(calendar))
                .toString();
    }

    private static void assertPhysicalLines(String text) {
        assertFalse(text.replace("\r\n", "").contains("\n"));
        assertFalse(text.replace("\r\n", "").contains("\r"));
        assertFalse(text.startsWith("\uFEFF"));
        assertTrue(text.endsWith("\r\n"));
        for (String line : text.split("\r\n", -1)) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75, line);
        }
    }

    private static String replaceImagePayload(String unfolded) {
        String prefix =
                "IMAGE;ENCODING=BASE64;VALUE=BINARY;DISPLAY=BADGE;FMTTYPE=image/png:";
        int start = unfolded.indexOf(prefix);
        assertTrue(start >= 0);
        int end = unfolded.indexOf("\r\n", start);
        assertTrue(end > start);
        return unfolded.substring(0, start) + prefix + "<png>" + unfolded.substring(end);
    }

    private static MockHttpServletRequest requestFromLink(String link) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String query = link.substring(link.indexOf('?') + 1);
        for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            request.addParameter(
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return request;
    }

    private static String decodedQueryValue(String link, String name) {
        String query = link.substring(link.indexOf('?') + 1);
        for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            if (name.equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing query value: " + name);
    }

    private static OpportunitySearchResponse response(
            String locationId,
            String displayName,
            OpportunitySearchResponse.Opportunity opportunity
    ) {
        return new OpportunitySearchResponse(
                "ok",
                GENERATED_AT,
                new OpportunitySearchResponse.Location(
                        locationId, "real_location", displayName,
                        50.08804, 14.42076, 202, "Europe/Prague", "CZ"),
                7,
                "2026-08-12T00:00:00Z",
                "2026-08-19T00:00:00Z",
                1,
                90.0,
                List.of(opportunity),
                List.of(),
                List.of());
    }

    private static OpportunitySearchResponse.Opportunity standardOpportunity(String id) {
        return opportunity(
                id,
                "2026-08-14T02:05:00Z",
                "2026-08-14T02:25:00Z",
                "2026-08-14T02:55:00Z",
                "mostly clear");
    }

    private static OpportunitySearchResponse.Opportunity opportunity(
            String id,
            String startsAt,
            String suggestedAt,
            String endsAt,
            String weatherSummary
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
                        27, 0.4, 10_000, 2, weatherSummary),
                null,
                "Exact score and provider details must not appear.",
                Map.of("ics", "/o/reserved.ics"));
    }
}
