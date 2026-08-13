package dev.moonservice.backend.web;

import com.github.benmanes.caffeine.cache.Cache;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtomFeedRichContentTest {
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    private static final String XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml";
    private static final String LOCATION_ID = "moon-service-3067696";
    private static final String UPDATED = "2026-08-12T18:30:00Z";

    @Test
    void rendersStandaloneSummaryAndValidXhtmlWithEmbeddedPreview() throws Exception {
        byte[] xml = feed(List.of(opportunity(
                Instant.parse("2026-08-14T02:05:00Z"),
                "waxing_crescent",
                7.0,
                72.0,
                241.0,
                18.0,
                61,
                "light rain")));
        String serialized = new String(xml, StandardCharsets.UTF_8);
        assertTrue(serialized.contains("<div xmlns=\"" + XHTML_NAMESPACE + "\">"));
        assertFalse(serialized.contains("<xhtml:"));
        Document document = parse(xml);
        Element entry = (Element) document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry").item(0);
        Element summary = atomChild(entry, "summary");
        assertEquals("text", summary.getAttribute("type"));
        assertTrue(summary.getTextContent().contains("When — Starts:"));
        assertTrue(summary.getTextContent().contains("Conditions — Phase: waxing crescent"));
        assertTrue(summary.getTextContent().contains("Before you go —"));
        assertTrue(summary.getTextContent().contains("🚨 Eye safety: Do not ever search for the Moon"));

        Element content = atomChild(entry, "content");
        assertEquals("xhtml", content.getAttribute("type"));
        NodeList divs = content.getElementsByTagNameNS(XHTML_NAMESPACE, "div");
        assertEquals(1, divs.getLength());
        Element div = (Element) divs.item(0);
        assertEquals(null, div.getPrefix());
        assertEquals(3, div.getElementsByTagNameNS(XHTML_NAMESPACE, "p").getLength() - 1);
        String xhtmlText = div.getTextContent();
        assertTrue(xhtmlText.contains("When"));
        assertTrue(xhtmlText.contains("Conditions"));
        assertTrue(xhtmlText.contains("Before you go"));
        assertTrue(xhtmlText.contains("Window: 2026-08-14T02:05:00Z to 2026-08-14T02:55:00Z"));

        NodeList strong = div.getElementsByTagNameNS(XHTML_NAMESPACE, "strong");
        List<String> strongText = new ArrayList<>();
        for (int index = 0; index < strong.getLength(); index++) {
            strongText.add(strong.item(index).getTextContent());
        }
        assertTrue(strongText.contains("🚨 Eye safety:"));
        assertTrue(strongText.contains("ever"));

        NodeList images = div.getElementsByTagNameNS(XHTML_NAMESPACE, "img");
        assertEquals(1, images.getLength());
        Element image = (Element) images.item(0);
        assertEquals(null, image.getPrefix());
        assertEquals("640", image.getAttribute("width"));
        assertEquals("160", image.getAttribute("height"));
        assertTrue(image.getAttribute("alt").contains("Moon path"));
        String source = image.getAttribute("src");
        assertTrue(source.startsWith("data:image/png;base64,"));
        assertFalse(source.contains("http://"));
        assertFalse(source.contains("https://"));
        assertFalse(image.hasAttribute("srcset"));
        byte[] png = Base64.getDecoder().decode(source.substring(source.indexOf(',') + 1));
        assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47},
                java.util.Arrays.copyOf(png, 4));
        var decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(640, decoded.getWidth());
        assertEquals(160, decoded.getHeight());
        assertEquals(0, div.getElementsByTagNameNS(XHTML_NAMESPACE, "script").getLength());
        assertEquals(0, div.getElementsByTagNameNS(XHTML_NAMESPACE, "style").getLength());
        assertEquals(0, div.getElementsByTagNameNS(XHTML_NAMESPACE, "table").getLength());
    }

    @Test
    void appliesTheThinMoonWarningAtTheApprovedBoundary() {
        assertTrue(displayed("new_moon", 42.0).eyeSafety());
        assertTrue(displayed("waxing_crescent", 10.0).eyeSafety());
        assertTrue(displayed("waning_crescent", 9.9).eyeSafety());
        assertFalse(displayed("waxing_crescent", 10.1).eyeSafety());
        assertFalse(displayed("waning_crescent", 10.2).eyeSafety());
        assertFalse(displayed("first_quarter", 50.0).eyeSafety());
    }

    @Test
    void stableVisibleModelIgnoresSubPixelAndSameWeatherCategoryChanges() {
        OpportunitySearchResponse.Opportunity first = opportunity(
                Instant.parse("2026-08-14T02:05:00Z"),
                "waxing_crescent", 7.01, 72.1, 241.1, 18.1, 61, "rain possible");
        OpportunitySearchResponse.Opportunity visuallySame = opportunity(
                Instant.parse("2026-08-14T02:05:00Z"),
                "waxing_crescent", 7.02, 72.4, 241.4, 18.4, 82, "rain possible");
        OpportunitySearchResponse.Opportunity differentWeatherPicture = opportunity(
                Instant.parse("2026-08-14T02:05:00Z"),
                "waxing_crescent", 7.02, 72.4, 241.4, 18.4, 0, "rain possible");

        assertEquals(displayed(first), displayed(visuallySame));
        assertNotEquals(displayed(first), displayed(differentWeatherPicture));
    }

    @Test
    void maximumFeedStaysBelowThePublicationCheckpoint() {
        List<OpportunitySearchResponse.Opportunity> opportunities = new ArrayList<>();
        Instant firstStart = Instant.parse("2026-08-14T02:05:00Z");
        for (int index = 0; index < 10; index++) {
            opportunities.add(opportunity(
                    firstStart.plus(index, ChronoUnit.DAYS),
                    index % 2 == 0 ? "waxing_crescent" : "waxing_gibbous",
                    7.0 + index,
                    72.0 + index,
                    241.0 + index,
                    18.0 + index,
                    new int[]{0, 2, 45, 61, 71, 95, 4, 1, 82, 86}[index],
                    "forecast condition " + index));
        }

        int oneEntryBytes = feed(opportunities.subList(0, 1)).length;
        int tenEntryBytes = feed(opportunities).length;

        assertTrue(tenEntryBytes <= 1_572_864,
                () -> "Ten-entry Atom fixture was " + tenEntryBytes + " bytes; one entry was "
                        + oneEntryBytes + " bytes.");
        System.out.println("Atom rich feed sizes: one=" + oneEntryBytes + ", ten=" + tenEntryBytes);
    }

    @Test
    void cacheRecordsTheExactRetainedXmlWeight() throws Exception {
        OpportunitySearchResponse.Opportunity opportunity = opportunity(
                Instant.parse("2026-08-14T02:05:00Z"),
                "waxing_crescent", 7.0, 72.0, 241.0, 18.0, 0, "clear");
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(null, LOCATION_ID, OpportunitySearchRequest.Order.SOONEST))
                .thenReturn(response(List.of(opportunity)));
        AtomFeedService service = new AtomFeedService(
                search, Clock.fixed(Instant.parse(UPDATED), ZoneOffset.UTC));

        AtomFeedService.AtomFeed feed = service.feed(LOCATION_ID);
        Cache<String, Object> cache = cache(service);
        cache.cleanUp();
        var eviction = cache.policy().eviction().orElseThrow();

        assertEquals(96L * 1024 * 1024, eviction.getMaximum());
        assertEquals(feed.xml().length, eviction.weightedSize().orElseThrow());
    }

    @Test
    void refreshUsesOnlyVisibleQuantizedTextAndPictureChanges() throws Exception {
        OpportunitySearchResponse.Opportunity first = opportunity(
                Instant.parse("2026-08-14T02:05:00Z"),
                "waxing_crescent", 7.01, 72.1, 241.1, 18.1, 61, "rain possible");
        OpportunitySearchResponse.Opportunity visuallySame = opportunity(
                Instant.parse("2026-08-14T02:05:00Z"),
                "waxing_crescent", 7.02, 72.4, 241.4, 18.4, 82, "rain possible");
        OpportunitySearchResponse.Opportunity changedWeather = opportunity(
                Instant.parse("2026-08-14T02:05:00Z"),
                "waxing_crescent", 7.02, 72.4, 241.4, 18.4, 0, "rain possible");
        Instant firstRefresh = Instant.parse("2026-08-12T18:30:00Z");
        Instant secondRefresh = firstRefresh.plus(2, ChronoUnit.HOURS);
        Instant thirdRefresh = secondRefresh.plus(2, ChronoUnit.HOURS);
        MutableClock clock = new MutableClock(firstRefresh);
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(null, LOCATION_ID, OpportunitySearchRequest.Order.SOONEST)).thenReturn(
                response(firstRefresh.toString(), List.of(first)),
                response(secondRefresh.toString(), List.of(visuallySame)),
                response(thirdRefresh.toString(), List.of(changedWeather)));
        AtomFeedService service = new AtomFeedService(search, clock);

        AtomFeedService.AtomFeed before = service.feed(LOCATION_ID);
        clock.set(secondRefresh);
        AtomFeedService.AtomFeed same = service.feed(LOCATION_ID);
        clock.set(thirdRefresh);
        AtomFeedService.AtomFeed changed = service.feed(LOCATION_ID);

        assertArrayEquals(before.xml(), same.xml());
        assertEquals(before.etag(), same.etag());
        assertNotEquals(before.etag(), changed.etag());
        assertEquals(firstRefresh.toString(), childText(parse(same.xml()).getDocumentElement(), "updated"));
        assertEquals(thirdRefresh.toString(),
                childText(parse(changed.xml()).getDocumentElement(), "updated"));
    }

    private static AtomFeedDocumentRenderer.DisplayedEntry displayed(
            String phase,
            double illumination
    ) {
        return displayed(opportunity(
                Instant.parse("2026-08-14T02:05:00Z"),
                phase, illumination, 72.0, 241.0, 18.0, 0, "clear"));
    }

    private static AtomFeedDocumentRenderer.DisplayedEntry displayed(
            OpportunitySearchResponse.Opportunity opportunity
    ) {
        return AtomFeedDocumentRenderer.displayedEntry(location(), opportunity);
    }

    private static byte[] feed(List<OpportunitySearchResponse.Opportunity> opportunities) {
        AtomFeedDocumentRenderer.FeedMetadata metadata =
                AtomFeedDocumentRenderer.metadata(location());
        List<AtomFeedDocumentRenderer.EntrySnapshot> entries = opportunities.stream()
                .map(AtomFeedRichContentTest::displayed)
                .map(entry -> new AtomFeedDocumentRenderer.EntrySnapshot(entry, UPDATED))
                .toList();
        return AtomFeedDocumentRenderer.render(metadata, entries, UPDATED);
    }

    private static OpportunitySearchResponse.Location location() {
        return new OpportunitySearchResponse.Location(
                LOCATION_ID, "real_location", "Prague", 50.08804, 14.42076,
                202, "Europe/Prague", "CZ");
    }

    private static OpportunitySearchResponse response(
            List<OpportunitySearchResponse.Opportunity> opportunities
    ) {
        return response(UPDATED, opportunities);
    }

    private static OpportunitySearchResponse response(
            String generatedAt,
            List<OpportunitySearchResponse.Opportunity> opportunities
    ) {
        return new OpportunitySearchResponse(
                "ok", generatedAt, location(), 7,
                "2026-08-12T00:00:00Z", "2026-08-19T00:00:00Z",
                opportunities.size(), 90.0, opportunities, List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static Cache<String, Object> cache(AtomFeedService service) throws Exception {
        Field field = AtomFeedService.class.getDeclaredField("states");
        field.setAccessible(true);
        return (Cache<String, Object>) field.get(service);
    }

    private static OpportunitySearchResponse.Opportunity opportunity(
            Instant startsAt,
            String phase,
            double illumination,
            double phaseAngle,
            Double brightLimb,
            Double northPole,
            int weatherCode,
            String weatherSummary
    ) {
        String start = startsAt.toString();
        String suggested = startsAt.plus(20, ChronoUnit.MINUTES).toString();
        String end = startsAt.plus(50, ChronoUnit.MINUTES).toString();
        OpportunitySearchResponse.MoonPathPoint startPoint = point(start, 4.0, phaseAngle,
                brightLimb, northPole, "start");
        OpportunitySearchResponse.MoonPathPoint suggestedPoint = point(suggested, 7.0, phaseAngle,
                brightLimb, northPole, "suggested");
        OpportunitySearchResponse.MoonPathPoint endPoint = point(end, 3.0, phaseAngle,
                brightLimb, northPole, "end");
        return new OpportunitySearchResponse.Opportunity(
                "volatile-id", "moonrise_low", null,
                start, suggested, end, "Europe/Prague", 83, "high", null, null,
                new OpportunitySearchResponse.Moon(
                        7.0, 91.0, illumination, phaseAngle, brightLimb, northPole, phase),
                new OpportunitySearchResponse.MoonPath(
                        startPoint, suggestedPoint, endPoint,
                        List.of(startPoint, suggestedPoint, endPoint)),
                new OpportunitySearchResponse.Sun(-5.0, 72.0, "civil_twilight"),
                new OpportunitySearchResponse.Weather(
                        "hourly", "window", 20, 30, 10, 10, 10,
                        20, 0.2, 10_000, weatherCode, weatherSummary),
                null, "private ranking reason", Map.of());
    }

    private static OpportunitySearchResponse.MoonPathPoint point(
            String at,
            double altitude,
            double phaseAngle,
            Double brightLimb,
            Double northPole,
            String role
    ) {
        return new OpportunitySearchResponse.MoonPathPoint(
                at, altitude, 91.0, phaseAngle, brightLimb, northPole,
                -5.0, 72.0, "civil_twilight", role);
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Element atomChild(Element parent, String localName) {
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
        return atomChild(parent, localName).getTextContent();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
