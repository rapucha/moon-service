package dev.moonservice.backend.web;

import com.github.benmanes.caffeine.cache.Cache;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AtomFeedPreferenceFunctionalTest {
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    private static final String LOCATION_ID = "moon-service:Praha / Žižkov";
    private static final String GENERATED_AT = "2026-08-12T12:00:00Z";
    private static final String ATOM_CONTENT_TYPE = "application/atom+xml;charset=UTF-8";
    private static final String PUBLIC_CACHE = "public, max-age=900";
    private static final String PRIVATE_CACHE = "private, max-age=900";
    private static final int FILTERED_WEIGHT = 96 * 1024;
    private static final long MAX_CACHE_WEIGHT = 96L * 1024 * 1024;

    private static final String LIGHT_BUCKET_PREFERENCES = """
            {"version":1,
             "altitudeDegrees":{"minimum":5,"maximum":20.5,"futureAltitude":true},
             "azimuthDegrees":{"included":{"start":350,"end":20},
                               "excluded":{"start":355,"end":5}},
             "time":{"mode":"light_bucket","buckets":["night","daylight"]},
             "namedPhases":["full_moon","new_moon"],
             "brightLimbOrientationDegrees":[{"start":20,"end":30},
                                               {"start":-0.0,"end":10},
                                               {"start":20,"end":30}],
             "future":{"value":true}}
            """;
    private static final String LOCAL_CLOCK_PREFERENCES = """
            {"version":1,"time":{"mode":"local_clock",
             "window":{"start":"22:15","end":"02:45"}}}
            """;

    @Test
    void lightBucketFieldsUseCanonicalSelfAndForwardIgnoredPathsToSoonestSearch() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                isNull(), eq(LOCATION_ID), eq(Order.SOONEST), any(OpportunityPreferences.class),
                anyList(), anyInt(), eq(WeatherRanking.PREFER_CLEAR)))
                .thenReturn(response(LOCATION_ID));
        Harness harness = harness(search);

        MvcResult result = harness.mvc().perform(filteredGet(
                        LOCATION_ID, LIGHT_BUCKET_PREFERENCES, "prefer_clear"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_CACHE))
                .andReturn();
        Document document = parse(result.getResponse().getContentAsByteArray());

        OpportunityPreferences expected = new OpportunityPreferences(
                1,
                new AltitudeRange(5, 20.5),
                new AzimuthPreference(
                        new DegreeRange(350, 20),
                        new DegreeRange(355, 5)),
                new TimePreference(
                        TimeMode.LIGHT_BUCKET,
                        null,
                        EnumSet.of(AmbientLight.DAYLIGHT, AmbientLight.NIGHT)),
                EnumSet.of(NamedPhase.NEW_MOON, NamedPhase.FULL_MOON),
                List.of(
                        new DegreeRange(20, 30),
                        new DegreeRange(-0.0, 10),
                        new DegreeRange(20, 30)));
        String selfPath = "/feeds/atom" + PublicPreferenceQuery.calendarQuery(
                LOCATION_ID, Order.BEST_MATCH, ProductWeatherRanking.PREFER_CLEAR, expected);

        assertEquals(selfPath, selfHref(document));
        assertFalse(selfPath.contains("future"));
        assertEquals(atomId("moon-service.atom.feed.v2\n" + selfPath), feedId(document));
        assertEquals(0, document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry").getLength());
        verify(search).search(
                isNull(), eq(LOCATION_ID), eq(Order.SOONEST), eq(expected),
                eq(List.of("/altitudeDegrees/futureAltitude", "/future")), eq(2),
                eq(WeatherRanking.PREFER_CLEAR));
    }

    @Test
    void localClockPreferencesSupportBalancedAndIgnoreWeatherSearches() throws Exception {
        String balancedLocation = "local-clock-balanced";
        String ignoredLocation = "local-clock-ignore-weather";
        OpportunityPreferences expected = new OpportunityPreferences(
                1,
                null,
                null,
                new TimePreference(
                        TimeMode.LOCAL_CLOCK,
                        new LocalClockWindow(LocalTime.of(22, 15), LocalTime.of(2, 45)),
                        null),
                null,
                null);
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                isNull(), eq(balancedLocation), eq(Order.SOONEST), eq(expected),
                eq(List.of()), eq(0), eq(WeatherRanking.BALANCED)))
                .thenReturn(response(balancedLocation));
        when(search.search(
                isNull(), eq(ignoredLocation), eq(Order.SOONEST), eq(expected),
                eq(List.of()), eq(0), eq(WeatherRanking.IGNORE_WEATHER)))
                .thenReturn(response(ignoredLocation));
        Harness harness = harness(search);

        Document balanced = parse(harness.mvc().perform(filteredGet(
                        balancedLocation, LOCAL_CLOCK_PREFERENCES, "balanced"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        Document ignored = parse(harness.mvc().perform(filteredGet(
                        ignoredLocation, LOCAL_CLOCK_PREFERENCES, "ignore_weather"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        String balancedSelf = selfHref(balanced);
        assertFalse(balancedSelf.contains("weatherRanking"));
        assertTrue(balancedSelf.contains("%2222%3A15%22"));
        assertTrue(selfHref(ignored).contains("&weatherRanking=ignore_weather&preferences="));
        verify(search).search(
                isNull(), eq(balancedLocation), eq(Order.SOONEST), eq(expected),
                eq(List.of()), eq(0), eq(WeatherRanking.BALANCED));
        verify(search).search(
                isNull(), eq(ignoredLocation), eq(Order.SOONEST), eq(expected),
                eq(List.of()), eq(0), eq(WeatherRanking.IGNORE_WEATHER));
    }

    @Test
    void weatherOnlyFeedUsesSoonestSearchAndPrivateVersionTwoIdentity() throws Exception {
        String locationId = "weather-only-ignore";
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                isNull(), eq(locationId), eq(Order.SOONEST), eq(WeatherRanking.IGNORE_WEATHER)))
                .thenReturn(response(locationId));

        MvcResult result = harness(search).mvc().perform(get("/feeds/atom")
                        .queryParam("locationId", locationId)
                        .queryParam("weatherRanking", "ignore_weather"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_CACHE))
                .andReturn();
        Document document = parse(result.getResponse().getContentAsByteArray());
        String selfPath = "/feeds/atom" + PublicPreferenceQuery.calendarQuery(
                locationId,
                Order.BEST_MATCH,
                ProductWeatherRanking.IGNORE_WEATHER,
                null);

        assertEquals(selfPath, selfHref(document));
        assertFalse(selfPath.contains("preferences"));
        assertEquals(atomId("moon-service.atom.feed.v2\n" + selfPath), feedId(document));
        verify(search).search(
                isNull(), eq(locationId), eq(Order.SOONEST), eq(WeatherRanking.IGNORE_WEATHER));
    }

    @Test
    void filteredFeedKeepsVersionOneEntryIdentity() throws Exception {
        String preferences = "{\"version\":1,\"namedPhases\":[\"full_moon\"]}";
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                isNull(), eq(LOCATION_ID), eq(Order.SOONEST), any(OpportunityPreferences.class),
                eq(List.of()), eq(0), eq(WeatherRanking.BALANCED)))
                .thenReturn(response(LOCATION_ID, List.of(opportunity())));

        Document document = parse(harness(search).mvc()
                .perform(preferenceGet(LOCATION_ID, preferences))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        NodeList entries = document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry");

        assertEquals(1, entries.getLength());
        Element entry = (Element) entries.item(0);
        assertEquals(atomId("moon-service.atom.entry.v1\n" + LOCATION_ID
                        + "\n2026-08-14T02:05:00Z"),
                entry.getElementsByTagNameNS(ATOM_NAMESPACE, "id").item(0).getTextContent());
    }

    @Test
    void invalidDuplicateUnknownAndOrderQueriesFailBeforeSearch() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        MockMvc mvc = harness(search).mvc();
        List<MockHttpServletRequestBuilder> invalidRequests = List.of(
                get("/feeds/atom")
                        .queryParam("locationId", LOCATION_ID)
                        .queryParam("preferences", "{\"version\":2}"),
                get("/feeds/atom")
                        .queryParam("locationId", LOCATION_ID)
                        .queryParam("preferences", "{not-json"),
                get("/feeds/atom")
                        .queryParam("locationId", LOCATION_ID)
                        .queryParam("preferences", "{\"version\":1,\"altitudeDegrees\":"
                                + "{\"minimum\":91,\"maximum\":92}}"),
                get("/feeds/atom")
                        .queryParam("locationId", LOCATION_ID)
                        .queryParam("weatherRanking", "storm_first"),
                get("/feeds/atom")
                        .queryParam("locationId", LOCATION_ID, LOCATION_ID),
                get("/feeds/atom")
                        .queryParam("locationId", LOCATION_ID)
                        .queryParam("unexpected", "value"),
                get("/feeds/atom")
                        .queryParam("locationId", LOCATION_ID)
                        .queryParam("order", "soonest"));

        for (MockHttpServletRequestBuilder request : invalidRequests) {
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }
        verifyNoInteractions(search);
    }

    @Test
    void equivalentNormalizedPreferencesShareStateAndDistinctFiltersGetNewV2Identity()
            throws Exception {
        String reordered = """
                {"version":1,"brightLimbOrientationDegrees":[
                 {"start":20,"end":30},{"start":0,"end":10},{"start":20,"end":30}]}
                """;
        String deduplicated = """
                {"version":1,"brightLimbOrientationDegrees":[
                 {"start":0,"end":10},{"start":20,"end":30}]}
                """;
        String distinct = """
                {"version":1,"brightLimbOrientationDegrees":[{"start":0,"end":11}]}
                """;
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                isNull(), eq(LOCATION_ID), eq(Order.SOONEST), any(OpportunityPreferences.class),
                eq(List.of()), eq(0), eq(WeatherRanking.BALANCED)))
                .thenReturn(response(LOCATION_ID));
        MockMvc mvc = harness(search).mvc();

        MvcResult first = mvc.perform(preferenceGet(LOCATION_ID, reordered))
                .andExpect(status().isOk()).andReturn();
        MvcResult equivalent = mvc.perform(preferenceGet(LOCATION_ID, deduplicated))
                .andExpect(status().isOk()).andReturn();
        MvcResult changed = mvc.perform(preferenceGet(LOCATION_ID, distinct))
                .andExpect(status().isOk()).andReturn();

        assertArrayEquals(first.getResponse().getContentAsByteArray(),
                equivalent.getResponse().getContentAsByteArray());
        assertEquals(first.getResponse().getHeader(HttpHeaders.ETAG),
                equivalent.getResponse().getHeader(HttpHeaders.ETAG));
        String firstId = feedId(parse(first.getResponse().getContentAsByteArray()));
        String changedId = feedId(parse(changed.getResponse().getContentAsByteArray()));
        assertNotEquals(firstId, changedId);
        assertNotEquals(first.getResponse().getHeader(HttpHeaders.ETAG),
                changed.getResponse().getHeader(HttpHeaders.ETAG));
        verify(search, times(2)).search(
                isNull(), eq(LOCATION_ID), eq(Order.SOONEST), any(OpportunityPreferences.class),
                eq(List.of()), eq(0), eq(WeatherRanking.BALANCED));
    }

    @Test
    void unknownOnlyPreferencesPopulateAndReuseUnfilteredV1StateWithPrivateCaching()
            throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq(LOCATION_ID), eq(Order.SOONEST)))
                .thenReturn(response(LOCATION_ID));
        MockMvc mvc = harness(search).mvc();

        MvcResult unknownOnly = mvc.perform(preferenceGet(
                        LOCATION_ID, "{\"version\":1,\"future\":{\"enabled\":true}}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_CACHE))
                .andReturn();
        MvcResult plain = mvc.perform(get("/feeds/atom").queryParam("locationId", LOCATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PUBLIC_CACHE))
                .andReturn();

        assertArrayEquals(plain.getResponse().getContentAsByteArray(),
                unknownOnly.getResponse().getContentAsByteArray());
        assertEquals(plain.getResponse().getHeader(HttpHeaders.ETAG),
                unknownOnly.getResponse().getHeader(HttpHeaders.ETAG));
        Document document = parse(unknownOnly.getResponse().getContentAsByteArray());
        assertEquals(atomId("moon-service.atom.feed.v1\n" + LOCATION_ID), feedId(document));
        assertEquals("/feeds/atom?locationId=moon-service:Praha%20/%20%C5%BDi%C5%BEkov",
                selfHref(document));
        verify(search).search(isNull(), eq(LOCATION_ID), eq(Order.SOONEST));
    }

    @Test
    void filteredHeadAndConditionalGetKeepPrivateHeadersWithoutBodiesOrLastModified()
            throws Exception {
        String preferences = "{\"version\":1,\"altitudeDegrees\":{\"minimum\":5,\"maximum\":20}}";
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                isNull(), eq(LOCATION_ID), eq(Order.SOONEST), any(OpportunityPreferences.class),
                eq(List.of()), eq(0), eq(WeatherRanking.BALANCED)))
                .thenReturn(response(LOCATION_ID));
        MockMvc mvc = harness(search).mvc();

        MvcResult getResult = mvc.perform(preferenceGet(LOCATION_ID, preferences))
                .andExpect(status().isOk()).andReturn();
        String etag = getResult.getResponse().getHeader(HttpHeaders.ETAG);
        assertNotNull(etag);
        MvcResult headResult = mvc.perform(head("/feeds/atom")
                        .queryParam("locationId", LOCATION_ID)
                        .queryParam("preferences", preferences))
                .andExpect(status().isOk()).andReturn();
        MvcResult notModified = mvc.perform(preferenceGet(LOCATION_ID, preferences)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified()).andReturn();

        for (MvcResult result : List.of(headResult, notModified)) {
            assertEquals(PRIVATE_CACHE, result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));
            assertEquals(ATOM_CONTENT_TYPE, result.getResponse().getHeader(HttpHeaders.CONTENT_TYPE));
            assertEquals(etag, result.getResponse().getHeader(HttpHeaders.ETAG));
            assertEquals(getResult.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH),
                    result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH));
            assertNull(result.getResponse().getHeader(HttpHeaders.LAST_MODIFIED));
            assertEquals(0, result.getResponse().getContentAsByteArray().length);
        }
        verify(search).search(
                isNull(), eq(LOCATION_ID), eq(Order.SOONEST), any(OpportunityPreferences.class),
                eq(List.of()), eq(0), eq(WeatherRanking.BALANCED));
    }

    @Test
    void filteredCacheUsesFloorAndCannotRetainMoreThan1024TinyStates() throws Exception {
        OpportunityPreferences preferences = new OpportunityPreferences(
                1, new AltitudeRange(5, 20), null, null, null, null);
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                isNull(), anyString(), eq(Order.SOONEST), eq(preferences),
                eq(List.of()), eq(0), eq(WeatherRanking.BALANCED)))
                .thenAnswer(invocation -> response(invocation.getArgument(1)));
        AtomFeedService service = new AtomFeedService(
                search, Clock.fixed(Instant.parse(GENERATED_AT), ZoneOffset.UTC));
        ProductRequestParser.IgnoredFields ignored =
                new ProductRequestParser.IgnoredFields(List.of(), 0);

        AtomFeedService.AtomFeed first = service.feed(new PublicPreferenceQuery.CalendarRequest(
                "filtered-cache-0", Order.BEST_MATCH, ProductWeatherRanking.BALANCED,
                preferences, ignored));
        Cache<Object, Object> cache = cache(service);
        cache.cleanUp();
        var eviction = cache.policy().eviction().orElseThrow();
        assertTrue(first.xml().length < FILTERED_WEIGHT);
        assertEquals(MAX_CACHE_WEIGHT, eviction.getMaximum());
        assertEquals(FILTERED_WEIGHT, eviction.weightedSize().orElseThrow());

        for (int index = 1; index <= 1024; index++) {
            service.feed(new PublicPreferenceQuery.CalendarRequest(
                    "filtered-cache-" + index,
                    Order.BEST_MATCH,
                    ProductWeatherRanking.BALANCED,
                    preferences,
                    ignored));
        }
        cache.cleanUp();

        assertTrue(cache.estimatedSize() <= 1024);
        assertTrue(eviction.weightedSize().orElseThrow() <= MAX_CACHE_WEIGHT);
    }

    @Test
    void plainLocationKeepsStripBeforeLengthWhileFilteredCodecUsesRawBound() throws Exception {
        String locationId = "x".repeat(100);
        String padded = " " + locationId + " ";
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(isNull(), eq(locationId), eq(Order.SOONEST)))
                .thenReturn(response(locationId));
        MockMvc mvc = harness(search).mvc();

        mvc.perform(get("/feeds/atom").queryParam("locationId", padded))
                .andExpect(status().isOk());
        mvc.perform(get("/feeds/atom")
                        .queryParam("locationId", padded)
                        .queryParam("weatherRanking", "balanced"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(search).search(isNull(), eq(locationId), eq(Order.SOONEST));
    }

    private static Harness harness(OpportunitySearchService search) {
        Clock clock = Clock.fixed(Instant.parse(GENERATED_AT), ZoneOffset.UTC);
        AtomFeedService service = new AtomFeedService(search, clock);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AtomFeedController(service, clock)).build();
        return new Harness(mvc);
    }

    private static MockHttpServletRequestBuilder filteredGet(
            String locationId,
            String preferences,
            String weatherRanking
    ) {
        return preferenceGet(locationId, preferences)
                .queryParam("weatherRanking", weatherRanking);
    }

    private static MockHttpServletRequestBuilder preferenceGet(
            String locationId,
            String preferences
    ) {
        return get("/feeds/atom")
                .queryParam("locationId", locationId)
                .queryParam("preferences", preferences);
    }

    private static OpportunitySearchResponse response(String locationId) {
        return response(locationId, List.of());
    }

    private static OpportunitySearchResponse response(
            String locationId,
            List<OpportunitySearchResponse.Opportunity> opportunities
    ) {
        return new OpportunitySearchResponse(
                "ok",
                GENERATED_AT,
                new OpportunitySearchResponse.Location(
                        locationId,
                        "real_location",
                        locationId,
                        50.08804,
                        14.42076,
                        202,
                        "Europe/Prague",
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

    private static OpportunitySearchResponse.Opportunity opportunity() {
        String startsAt = "2026-08-14T02:05:00Z";
        String suggestedAt = "2026-08-14T02:25:00Z";
        String endsAt = "2026-08-14T02:55:00Z";
        OpportunitySearchResponse.MoonPathPoint start = pathPoint(startsAt, 4.0, "start");
        OpportunitySearchResponse.MoonPathPoint suggested =
                pathPoint(suggestedAt, 7.0, "suggested");
        OpportunitySearchResponse.MoonPathPoint end = pathPoint(endsAt, 3.0, "end");
        return new OpportunitySearchResponse.Opportunity(
                "volatile-id", "moonrise_low", null,
                startsAt, suggestedAt, endsAt, "Europe/Prague", 83, "high", null, null,
                new OpportunitySearchResponse.Moon(
                        7.0, 91.0, 100.0, 180.0, 0.0, 0.0, "full_moon"),
                new OpportunitySearchResponse.MoonPath(
                        start, suggested, end, List.of(start, suggested, end)),
                new OpportunitySearchResponse.Sun(-5.0, 72.0, "civil_twilight"),
                new OpportunitySearchResponse.Weather(
                        "hourly", "partly_cloudy", 20, 30, 10, 10, 10,
                        20, 0.2, 10_000, 2, "partly cloudy"),
                null, "private ranking reason", Map.of());
    }

    private static OpportunitySearchResponse.MoonPathPoint pathPoint(
            String at,
            double altitude,
            String role
    ) {
        return new OpportunitySearchResponse.MoonPathPoint(
                at, altitude, 91.0, 180.0, 0.0, 0.0,
                -5.0, 72.0, "civil_twilight", role);
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static String feedId(Document document) {
        return document.getDocumentElement()
                .getElementsByTagNameNS(ATOM_NAMESPACE, "id")
                .item(0)
                .getTextContent();
    }

    private static String selfHref(Document document) {
        NodeList links = document.getDocumentElement()
                .getElementsByTagNameNS(ATOM_NAMESPACE, "link");
        for (int index = 0; index < links.getLength(); index++) {
            Element link = (Element) links.item(index);
            if ("self".equals(link.getAttribute("rel"))) {
                return link.getAttribute("href");
            }
        }
        throw new AssertionError("Missing Atom self link.");
    }

    private static String atomId(String seed) {
        return "urn:uuid:" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Cache<Object, Object> cache(AtomFeedService service) throws Exception {
        Field field = AtomFeedService.class.getDeclaredField("states");
        field.setAccessible(true);
        return (Cache<Object, Object>) field.get(service);
    }

    private record Harness(MockMvc mvc) {
    }
}
