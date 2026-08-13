package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.LocationCandidatesResponse;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.opportunity.search.OpportunityStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AtomFeedFunctionalTest {
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    private static final String LOCATION_ID = "moon-service-3067696";
    private static final String GENERATED_1 = "2026-08-12T12:00:00Z";
    private static final String GENERATED_2 = "2026-08-12T14:00:00Z";
    private static final String GENERATED_3 = "2026-08-12T16:00:00Z";

    @Test
    void returnsUsefulEscapedAtomInPreciseTimeOrder() throws Exception {
        String canonicalId = "moon-service:3067696&west";
        OpportunitySearchResponse.Opportunity later = opportunity(
                "2026-08-14T03:05:00Z", "2026-08-14T03:25:00Z", "2026-08-14T03:55:00Z",
                "medium", "partly cloudy", 7.2, 83.7, "civil_twilight");
        OpportunitySearchResponse.Opportunity earlier = opportunity(
                "2026-08-14T02:05:00Z", "2026-08-14T02:25:00Z", "2026-08-14T02:55:00Z",
                "high", "mostly clear", 5.5, 84.2, "civil_twilight");
        OpportunitySearchService search = searchService();
        whenSearch(search).thenReturn(response(
                GENERATED_1, canonicalId, "Prague & <Old Town>", List.of(later, earlier)));
        Harness harness = harness(search);

        MvcResult result = harness.mvc().perform(get("/feeds/atom")
                        .param("locationId", "  " + canonicalId + "  "))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/atom+xml;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=900"))
                .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED))
                .andReturn();

        byte[] xml = result.getResponse().getContentAsByteArray();
        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        assertEquals(strongEtag(xml), etag);
        Document document = parse(xml);
        Element feed = document.getDocumentElement();
        assertEquals(ATOM_NAMESPACE, feed.getNamespaceURI());
        assertEquals("Moon opportunities near Prague & <Old Town>", childText(feed, "title"));
        assertEquals(atomId("moon-service.atom.feed.v1\n" + canonicalId), childText(feed, "id"));
        assertEquals("Moon Service", childText(child(feed, "author"), "name"));
        assertEquals("/feeds/atom?locationId=moon-service:3067696%26west",
                child(feed, "link").getAttribute("href"));

        NodeList entries = feed.getElementsByTagNameNS(ATOM_NAMESPACE, "entry");
        assertEquals(2, entries.getLength());
        Element first = (Element) entries.item(0);
        assertEquals("2026-08-14 04:25 CEST — Moon opportunity near Prague & <Old Town>",
                childText(first, "title"));
        assertEquals(atomId("moon-service.atom.entry.v1\n" + canonicalId
                + "\n2026-08-14T02:05:00Z"), childText(first, "id"));
        assertEquals("/search?locationId=moon-service:3067696%26west",
                child(first, "link").getAttribute("href"));
        String summary = childText(first, "summary");
        assertTrue(summary.contains("Starts: 2026-08-14T02:05:00Z"));
        assertTrue(summary.contains("Suggested: 2026-08-14T02:25:00Z"));
        assertTrue(summary.contains("Ends: 2026-08-14T02:55:00Z"));
        assertTrue(summary.contains("Timezone: Europe/Prague"));
        assertTrue(summary.contains("Confidence: high"));
        assertTrue(summary.contains("Weather: mostly clear"));
        assertTrue(summary.contains("Moon altitude: 5.5 degrees"));
        assertTrue(summary.contains("Moon illumination: 84.2%"));
        assertTrue(summary.contains("Ambient light: civil twilight"));
        assertTrue(summary.contains("Local hills, buildings, or trees"));
        assertTrue(summary.contains("Open the live result before leaving"));
        assertFalse(summary.contains("cloud cover"));
        assertFalse(summary.contains("precipitation"));
        assertFalse(summary.contains("checkedAt"));
        assertFalse(summary.contains("Exact score"));
        verify(search).search(null, canonicalId, OpportunitySearchRequest.Order.SOONEST);
    }

    @Test
    void unchangedRefreshPreservesXmlAndSupportsConditionalGetAndHead() throws Exception {
        OpportunitySearchService search = searchService();
        OpportunitySearchResponse.Opportunity opportunity = standardOpportunity("mostly clear");
        whenSearch(search).thenReturn(
                response(GENERATED_1, LOCATION_ID, "Prague", List.of(opportunity)),
                response(GENERATED_2, LOCATION_ID, "Prague", List.of(opportunity)));
        Harness harness = harness(search);

        MvcResult first = getFeed(harness.mvc(), LOCATION_ID);
        harness.clock().advance(Duration.ofMinutes(61));
        MvcResult second = getFeed(harness.mvc(), LOCATION_ID);
        assertArrayEquals(first.getResponse().getContentAsByteArray(), second.getResponse().getContentAsByteArray());
        assertEquals(first.getResponse().getHeader(HttpHeaders.ETAG),
                second.getResponse().getHeader(HttpHeaders.ETAG));

        String etag = second.getResponse().getHeader(HttpHeaders.ETAG);
        harness.mvc().perform(get("/feeds/atom")
                        .param("locationId", LOCATION_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, "\"other\", W/" + etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=900"))
                .andExpect(content().bytes(new byte[0]));
        harness.mvc().perform(head("/feeds/atom").param("locationId", LOCATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/atom+xml;charset=UTF-8"))
                .andExpect(header().longValue(
                        HttpHeaders.CONTENT_LENGTH,
                        second.getResponse().getContentAsByteArray().length))
                .andExpect(content().bytes(new byte[0]));
        verify(search, times(2)).search(null, LOCATION_ID, OpportunitySearchRequest.Order.SOONEST);
    }

    @Test
    void meaningfulChangeUpdatesOnlyTheChangedEntryAndFeed() throws Exception {
        OpportunitySearchResponse.Opportunity first = standardOpportunity("mostly clear");
        OpportunitySearchResponse.Opportunity second = opportunity(
                "2026-08-15T02:05:00Z", "2026-08-15T02:25:00Z", "2026-08-15T02:55:00Z",
                "medium", "cloudy", 6.1, 82.0, "civil_twilight");
        OpportunitySearchService search = searchService();
        whenSearch(search).thenReturn(
                response(GENERATED_1, LOCATION_ID, "Prague", List.of(first, second)),
                response(GENERATED_2, LOCATION_ID, "Prague",
                        List.of(standardOpportunity("partly cloudy"), second)));
        Harness harness = harness(search);

        MvcResult before = getFeed(harness.mvc(), LOCATION_ID);
        harness.clock().advance(Duration.ofMinutes(61));
        MvcResult after = getFeed(harness.mvc(), LOCATION_ID);
        Document document = parse(after.getResponse().getContentAsByteArray());
        String firstId = entryId(LOCATION_ID, first.startsAt());
        String secondId = entryId(LOCATION_ID, second.startsAt());
        assertEquals(GENERATED_2, childText(document.getDocumentElement(), "updated"));
        assertEquals(GENERATED_2, entryUpdated(document, firstId));
        assertEquals(GENERATED_1, entryUpdated(document, secondId));
        assertNotEquals(before.getResponse().getHeader(HttpHeaders.ETAG),
                after.getResponse().getHeader(HttpHeaders.ETAG));
    }

    @Test
    void returnsValidEmptyFeed() throws Exception {
        OpportunitySearchService search = searchService();
        whenSearch(search).thenReturn(response(GENERATED_1, LOCATION_ID, "Prague", List.of()));

        Document document = parse(getFeed(harness(search).mvc(), LOCATION_ID)
                .getResponse().getContentAsByteArray());

        assertEquals(GENERATED_1, childText(document.getDocumentElement(), "updated"));
        assertEquals(0, document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry").getLength());
    }

    @Test
    void rejectsInvalidLocationIdsWithoutSearching() throws Exception {
        OpportunitySearchService search = searchService();
        MockMvc mvc = harness(search).mvc();

        expectError(mvc, get("/feeds/atom"), 400, "invalid_request");
        expectError(mvc, get("/feeds/atom").param("locationId", "   "), 400, "invalid_request");
        expectError(mvc, get("/feeds/atom").param("locationId", "abc\u0001def"), 400, "invalid_request");
        expectError(mvc, get("/feeds/atom").param("locationId", "abc\u202Edef"), 400, "invalid_request");
        expectError(mvc, get("/feeds/atom").param("locationId", "x".repeat(101)), 400, "invalid_request");
        verifyNoInteractions(search);
    }

    @Test
    void mapsLookupAndProviderFailuresToSafeNoStoreErrors() throws Exception {
        OpportunitySearchService search = searchService();
        whenSearch(search).thenAnswer(invocation -> switch (invocation.getArgument(1, String.class)) {
            case "unknown" -> OpportunityStatusResponse.locationNotFound();
            case "ambiguous" -> LocationCandidatesResponse.ambiguous(List.of());
            case "unavailable" -> OpportunityStatusResponse.temporarilyUnavailable("provider detail");
            case "boom" -> throw new IllegalStateException("provider-token-value");
            case "invalid-provider-text" -> response(
                    GENERATED_1, LOCATION_ID, "Prague\u0001provider-secret", List.of());
            default -> throw new AssertionError("Unexpected test location ID.");
        });
        MockMvc mvc = harness(search).mvc();

        expectError(mvc, get("/feeds/atom").param("locationId", "unknown"),
                404, "location_not_found");
        expectError(mvc, get("/feeds/atom").param("locationId", "ambiguous"),
                503, "temporarily_unavailable");
        expectError(mvc, get("/feeds/atom").param("locationId", "unavailable"),
                503, "temporarily_unavailable");
        MvcResult failed = expectError(mvc, get("/feeds/atom").param("locationId", "boom"),
                503, "temporarily_unavailable");
        String body = failed.getResponse().getContentAsString();
        assertFalse(body.contains("boom"));
        assertFalse(body.contains("provider-token-value"));
        MvcResult invalidProviderText = expectError(
                mvc, get("/feeds/atom").param("locationId", "invalid-provider-text"),
                503, "temporarily_unavailable");
        assertFalse(invalidProviderText.getResponse().getContentAsString().contains("provider-secret"));
    }

    @Test
    void failedStaleRefreshReturnsErrorKeepsComparisonAndRetries() throws Exception {
        OpportunitySearchResponse unchanged = response(
                GENERATED_1, LOCATION_ID, "Prague", List.of(standardOpportunity("mostly clear")));
        OpportunitySearchService search = searchService();
        whenSearch(search).thenReturn(
                unchanged,
                OpportunityStatusResponse.temporarilyUnavailable("hidden provider detail"),
                response(GENERATED_3, LOCATION_ID, "Prague", List.of(standardOpportunity("mostly clear"))));
        Harness harness = harness(search);

        MvcResult first = getFeed(harness.mvc(), LOCATION_ID);
        harness.clock().advance(Duration.ofMinutes(61));
        MvcResult failure = expectError(
                harness.mvc(), get("/feeds/atom").param("locationId", LOCATION_ID),
                503, "temporarily_unavailable");
        assertFalse(failure.getResponse().getContentAsString().contains("Moon opportunities near"));
        MvcResult retry = getFeed(harness.mvc(), LOCATION_ID);
        assertArrayEquals(first.getResponse().getContentAsByteArray(), retry.getResponse().getContentAsByteArray());
        verify(search, times(3)).search(null, LOCATION_ID, OpportunitySearchRequest.Order.SOONEST);
    }

    @Test
    void sameLocationSharesRefreshWhileAnotherLocationContinues() throws Exception {
        OpportunitySearchService search = searchService();
        CountDownLatch blockedSearchStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockedSearch = new CountDownLatch(1);
        AtomicInteger firstLocationCalls = new AtomicInteger();
        AtomicInteger secondLocationCalls = new AtomicInteger();
        whenSearch(search).thenAnswer(invocation -> {
            String id = invocation.getArgument(1, String.class);
            if (LOCATION_ID.equals(id)) {
                firstLocationCalls.incrementAndGet();
                blockedSearchStarted.countDown();
                assertTrue(releaseBlockedSearch.await(5, TimeUnit.SECONDS));
            } else {
                secondLocationCalls.incrementAndGet();
            }
            return response(GENERATED_1, id, id, List.of(standardOpportunity("mostly clear")));
        });
        Harness harness = harness(search);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CyclicBarrier sameLocationStart = new CyclicBarrier(2);

        try {
            Future<AtomFeedService.AtomFeed> first = executor.submit(() -> {
                sameLocationStart.await();
                return harness.feeds().feed(LOCATION_ID);
            });
            Future<AtomFeedService.AtomFeed> follower = executor.submit(() -> {
                sameLocationStart.await();
                return harness.feeds().feed(LOCATION_ID);
            });
            assertTrue(blockedSearchStarted.await(5, TimeUnit.SECONDS));
            Future<AtomFeedService.AtomFeed> other =
                    executor.submit(() -> harness.feeds().feed("moon-service-other"));
            assertNotNull(other.get(2, TimeUnit.SECONDS));
            releaseBlockedSearch.countDown();
            assertArrayEquals(first.get(5, TimeUnit.SECONDS).xml(), follower.get(5, TimeUnit.SECONDS).xml());
            assertEquals(1, firstLocationCalls.get());
            assertEquals(1, secondLocationCalls.get());
        } finally {
            releaseBlockedSearch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void removedEntryIsNewWhenItReappears() throws Exception {
        OpportunitySearchResponse.Opportunity first = standardOpportunity("mostly clear");
        OpportunitySearchResponse.Opportunity second = opportunity(
                "2026-08-15T02:05:00Z", "2026-08-15T02:25:00Z", "2026-08-15T02:55:00Z",
                "medium", "cloudy", 6.1, 82.0, "civil_twilight");
        OpportunitySearchService search = searchService();
        whenSearch(search).thenReturn(
                response(GENERATED_1, LOCATION_ID, "Prague", List.of(first, second)),
                response(GENERATED_2, LOCATION_ID, "Prague", List.of(second)),
                response(GENERATED_3, LOCATION_ID, "Prague", List.of(first, second)));
        Harness harness = harness(search);

        getFeed(harness.mvc(), LOCATION_ID);
        harness.clock().advance(Duration.ofMinutes(61));
        getFeed(harness.mvc(), LOCATION_ID);
        harness.clock().advance(Duration.ofMinutes(61));
        Document reappeared = parse(getFeed(harness.mvc(), LOCATION_ID)
                .getResponse().getContentAsByteArray());

        assertEquals(GENERATED_3, entryUpdated(reappeared, entryId(LOCATION_ID, first.startsAt())));
        assertEquals(GENERATED_1, entryUpdated(reappeared, entryId(LOCATION_ID, second.startsAt())));
        assertEquals(GENERATED_3, childText(reappeared.getDocumentElement(), "updated"));
    }

    @Test
    void missingProcessStateKeepsIdsButResetsUpdateTimes() throws Exception {
        OpportunitySearchResponse.Opportunity opportunity = standardOpportunity("mostly clear");
        OpportunitySearchService search = searchService();
        whenSearch(search).thenReturn(
                response(GENERATED_1, LOCATION_ID, "Prague", List.of(opportunity)),
                response(GENERATED_2, LOCATION_ID, "Prague", List.of(opportunity)));
        MutableClock clock = new MutableClock(Instant.parse(GENERATED_1));

        AtomFeedService firstProcess = new AtomFeedService(search, clock);
        Document first = parse(firstProcess.feed(LOCATION_ID).xml());
        AtomFeedService nextProcess = new AtomFeedService(search, clock);
        Document next = parse(nextProcess.feed(LOCATION_ID).xml());

        assertEquals(childText(first.getDocumentElement(), "id"), childText(next.getDocumentElement(), "id"));
        assertEquals(childText((Element) first.getElementsByTagNameNS(ATOM_NAMESPACE, "entry").item(0), "id"),
                childText((Element) next.getElementsByTagNameNS(ATOM_NAMESPACE, "entry").item(0), "id"));
        assertEquals(GENERATED_1, childText(first.getDocumentElement(), "updated"));
        assertEquals(GENERATED_2, childText(next.getDocumentElement(), "updated"));
        assertNotEquals(strongEtag(firstProcess.feed(LOCATION_ID).xml()),
                strongEtag(nextProcess.feed(LOCATION_ID).xml()));
    }

    private static Harness harness(OpportunitySearchService search) {
        MutableClock clock = new MutableClock(Instant.parse(GENERATED_1));
        AtomFeedService feeds = new AtomFeedService(search, clock);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AtomFeedController(feeds, clock)).build();
        return new Harness(mvc, feeds, clock);
    }

    private static OpportunitySearchService searchService() {
        return mock(OpportunitySearchService.class);
    }

    private static org.mockito.stubbing.OngoingStubbing<OpportunityResponse> whenSearch(
            OpportunitySearchService search
    ) {
        return when(search.search(
                isNull(),
                anyString(),
                eq(OpportunitySearchRequest.Order.SOONEST)));
    }

    private static MvcResult getFeed(MockMvc mvc, String locationId) throws Exception {
        return mvc.perform(get("/feeds/atom").param("locationId", locationId))
                .andExpect(status().isOk())
                .andReturn();
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

    private static OpportunitySearchResponse response(
            String generatedAt,
            String locationId,
            String displayName,
            List<OpportunitySearchResponse.Opportunity> opportunities
    ) {
        return new OpportunitySearchResponse(
                "ok",
                generatedAt,
                new OpportunitySearchResponse.Location(
                        locationId, "real_location", displayName,
                        50.08804, 14.42076, 202, "Europe/Prague", "CZ"),
                7,
                "2026-08-12T00:00:00Z",
                "2026-08-19T00:00:00Z",
                opportunities.size(),
                90.0,
                opportunities,
                List.of(),
                List.of());
    }

    private static OpportunitySearchResponse.Opportunity standardOpportunity(String weatherSummary) {
        return opportunity(
                "2026-08-14T02:05:00Z", "2026-08-14T02:25:00Z", "2026-08-14T02:55:00Z",
                "high", weatherSummary, 5.5, 84.2, "civil_twilight");
    }

    private static OpportunitySearchResponse.Opportunity opportunity(
            String startsAt,
            String suggestedAt,
            String endsAt,
            String confidence,
            String weatherSummary,
            double altitude,
            double illumination,
            String lightBucket
    ) {
        return new OpportunitySearchResponse.Opportunity(
                "volatile-opportunity-id",
                "moonrise_low",
                null,
                startsAt,
                suggestedAt,
                endsAt,
                "Europe/Prague",
                83,
                confidence,
                null,
                null,
                new OpportunitySearchResponse.Moon(
                        altitude, 91.0, illumination, 162.0, null, null, "waxing_gibbous"),
                moonPath(startsAt, suggestedAt, endsAt, altitude, lightBucket),
                new OpportunitySearchResponse.Sun(-5.0, 72.0, lightBucket),
                new OpportunitySearchResponse.Weather(
                        "hourly", "partly_cloudy", 63, 71, 30, 44, 51,
                        27, 0.4, 10_000, 2, weatherSummary),
                null,
                "Exact score and provider details must not appear.",
                Map.of());
    }

    private static OpportunitySearchResponse.MoonPath moonPath(
            String startsAt,
            String suggestedAt,
            String endsAt,
            double suggestedAltitude,
            String lightBucket
    ) {
        OpportunitySearchResponse.MoonPathPoint start = pathPoint(
                startsAt, suggestedAltitude - 2.0, lightBucket, "start");
        OpportunitySearchResponse.MoonPathPoint suggested = pathPoint(
                suggestedAt, suggestedAltitude, lightBucket, "suggested");
        OpportunitySearchResponse.MoonPathPoint end = pathPoint(
                endsAt, suggestedAltitude - 3.0, lightBucket, "end");
        return new OpportunitySearchResponse.MoonPath(
                start, suggested, end, List.of(start, suggested, end));
    }

    private static OpportunitySearchResponse.MoonPathPoint pathPoint(
            String at,
            double altitude,
            String lightBucket,
            String role
    ) {
        return new OpportunitySearchResponse.MoonPathPoint(
                at, altitude, 91.0, 162.0, null, null,
                -5.0, 72.0, lightBucket, role);
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
    }

    private static Element child(Element parent, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element
                    && ATOM_NAMESPACE.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        throw new AssertionError("Missing Atom element: " + localName);
    }

    private static String childText(Element parent, String localName) {
        return child(parent, localName).getTextContent();
    }

    private static String entryUpdated(Document document, String entryId) {
        NodeList entries = document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry");
        for (int index = 0; index < entries.getLength(); index++) {
            Element entry = (Element) entries.item(index);
            if (entryId.equals(childText(entry, "id"))) {
                return childText(entry, "updated");
            }
        }
        throw new AssertionError("Missing Atom entry: " + entryId);
    }

    private static String entryId(String locationId, String startsAt) {
        return atomId("moon-service.atom.entry.v1\n" + locationId + "\n" + startsAt);
    }

    private static String atomId(String input) {
        return "urn:uuid:" + UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String strongEtag(byte[] body) throws Exception {
        return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)) + "\"";
    }

    private record Harness(MockMvc mvc, AtomFeedService feeds, MutableClock clock) {
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
