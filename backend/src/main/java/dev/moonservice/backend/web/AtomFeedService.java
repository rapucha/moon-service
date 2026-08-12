package dev.moonservice.backend.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
final class AtomFeedService {
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    private static final String AUTHOR = "Moon Service";
    private static final String HORIZON_CAVEAT =
            "Local hills, buildings, or trees may affect exact visibility near the horizon.";
    private static final String LIVE_RESULT_WARNING =
            "Open the live result before leaving because forecasts and recommendations can change.";
    private static final int MAX_LOCATION_ID_CODE_POINTS = 100;
    private static final int MAX_STATES = 1_000;
    private static final Duration FRESHNESS = Duration.ofHours(1);
    private static final DateTimeFormatter TITLE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z", Locale.ENGLISH);

    private final OpportunitySearchService opportunitySearchService;
    private final Clock clock;
    private final Cache<String, FeedState> states = Caffeine.newBuilder()
            .maximumSize(MAX_STATES)
            .build();

    AtomFeedService(OpportunitySearchService opportunitySearchService, Clock clock) {
        this.opportunitySearchService = Objects.requireNonNull(opportunitySearchService, "opportunitySearchService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    AtomFeed feed(String rawLocationId) {
        String locationId = normalizeLocationId(rawLocationId);
        FeedState state = states.get(locationId, ignored -> new FeedState());
        FeedSnapshot snapshot = state.current(
                clock.instant(),
                previous -> refresh(locationId, previous));
        return new AtomFeed(snapshot.xml(), snapshot.etag());
    }

    private FeedSnapshot refresh(String locationId, FeedSnapshot previous) {
        OpportunityResponse response;
        try {
            response = opportunitySearchService.search(
                    null,
                    locationId,
                    OpportunitySearchRequest.Order.SOONEST);
        } catch (InvalidOpportunitySearchRequestException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw FeedFailure.temporarilyUnavailable();
        }

        if (!(response instanceof OpportunitySearchResponse success) || !"ok".equals(success.status())) {
            if (response != null && "location_not_found".equals(response.status())) {
                throw FeedFailure.locationNotFound();
            }
            throw FeedFailure.temporarilyUnavailable();
        }

        String generatedAt = Instant.parse(success.generatedAt()).toString();
        FeedMetadata metadata = metadata(success.location());
        List<DisplayedEntry> displayedEntries = success.opportunities().stream()
                .map(opportunity -> displayedEntry(success.location(), opportunity))
                .sorted(Comparator
                        .comparing((DisplayedEntry entry) -> Instant.parse(entry.suggestedAt()))
                        .thenComparing(DisplayedEntry::id))
                .limit(10)
                .toList();

        Map<String, EntrySnapshot> previousEntries = previous == null
                ? Map.of()
                : previous.entries().stream().collect(Collectors.toMap(
                        entry -> entry.displayed().id(), Function.identity()));
        List<EntrySnapshot> entries = displayedEntries.stream()
                .map(displayed -> entrySnapshot(displayed, previousEntries.get(displayed.id()), generatedAt))
                .toList();
        String feedUpdated = previous == null
                || !metadata.equals(previous.metadata())
                || !entries.equals(previous.entries())
                ? generatedAt
                : previous.feedUpdated();

        byte[] xml = render(metadata, entries, feedUpdated);
        return new FeedSnapshot(
                metadata,
                entries,
                feedUpdated,
                clock.instant().plus(FRESHNESS),
                xml,
                etag(xml));
    }

    private static FeedMetadata metadata(OpportunitySearchResponse.Location location) {
        String canonicalId = location.id();
        String encodedId = UriUtils.encodeQueryParam(canonicalId, StandardCharsets.UTF_8);
        return new FeedMetadata(
                "Moon opportunities near " + location.displayName(),
                atomId("moon-service.atom.feed.v1\n" + canonicalId),
                AUTHOR,
                "/feeds/atom?locationId=" + encodedId);
    }

    private static DisplayedEntry displayedEntry(
            OpportunitySearchResponse.Location location,
            OpportunitySearchResponse.Opportunity opportunity
    ) {
        String canonicalId = location.id();
        String encodedId = UriUtils.encodeQueryParam(canonicalId, StandardCharsets.UTF_8);
        String title = TITLE_TIME.format(Instant.parse(opportunity.suggestedAt())
                        .atZone(ZoneId.of(location.timezone())))
                + " — Moon opportunity near " + location.displayName();
        String summary = "Starts: " + opportunity.startsAt() + "\n"
                + "Suggested: " + opportunity.suggestedAt() + "\n"
                + "Ends: " + opportunity.endsAt() + "\n"
                + "Timezone: " + location.timezone() + "\n"
                + "Confidence: " + plainLabel(opportunity.confidence()) + "\n"
                + "Weather: " + opportunity.weather().summary() + "\n"
                + "Moon altitude: " + oneDecimal(opportunity.moon().altitudeDegrees()) + " degrees\n"
                + "Moon illumination: " + oneDecimal(opportunity.moon().illuminationPercent()) + "%\n"
                + "Ambient light: " + plainLabel(opportunity.sun().lightBucket()) + "\n"
                + HORIZON_CAVEAT + "\n"
                + LIVE_RESULT_WARNING;
        return new DisplayedEntry(
                atomId("moon-service.atom.entry.v1\n" + canonicalId + "\n" + opportunity.startsAt()),
                title,
                summary,
                "/search?locationId=" + encodedId,
                opportunity.suggestedAt());
    }

    private static EntrySnapshot entrySnapshot(
            DisplayedEntry displayed,
            EntrySnapshot previous,
            String generatedAt
    ) {
        String updated = previous != null && previous.displayed().equals(displayed)
                ? previous.updated()
                : generatedAt;
        return new EntrySnapshot(displayed, updated);
    }

    private static byte[] render(
            FeedMetadata metadata,
            List<EntrySnapshot> entries,
            String feedUpdated
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory()
                    .createXMLStreamWriter(output, StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement("feed");
            xml.writeDefaultNamespace(ATOM_NAMESPACE);
            textElement(xml, "title", metadata.title());
            textElement(xml, "id", metadata.id());
            textElement(xml, "updated", feedUpdated);
            xml.writeStartElement("author");
            textElement(xml, "name", metadata.author());
            xml.writeEndElement();
            link(xml, "self", metadata.selfUrl(), "application/atom+xml");
            for (EntrySnapshot entry : entries) {
                xml.writeStartElement("entry");
                textElement(xml, "id", entry.displayed().id());
                textElement(xml, "title", entry.displayed().title());
                textElement(xml, "updated", entry.updated());
                link(xml, "alternate", entry.displayed().alternateUrl(), "text/html");
                xml.writeStartElement("summary");
                xml.writeAttribute("type", "text");
                xml.writeCharacters(requireXmlText(entry.displayed().summary()));
                xml.writeEndElement();
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("Could not render Atom XML.", ex);
        }
        return output.toByteArray();
    }

    private static void textElement(XMLStreamWriter xml, String name, String value)
            throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(requireXmlText(value));
        xml.writeEndElement();
    }

    private static void link(XMLStreamWriter xml, String rel, String href, String type)
            throws XMLStreamException {
        xml.writeEmptyElement("link");
        xml.writeAttribute("rel", requireXmlText(rel));
        xml.writeAttribute("href", requireXmlText(href));
        xml.writeAttribute("type", requireXmlText(type));
    }

    private static String requireXmlText(String value) {
        boolean valid = value.codePoints().allMatch(codePoint ->
                codePoint == 0x9
                        || codePoint == 0xA
                        || codePoint == 0xD
                        || codePoint >= 0x20 && codePoint <= 0xD7FF
                        || codePoint >= 0xE000 && codePoint <= 0xFFFD
                        || codePoint >= 0x10000 && codePoint <= 0x10FFFF);
        if (!valid) {
            throw new IllegalArgumentException(
                    "Atom text contains a character that XML 1.0 cannot represent.");
        }
        return value;
    }

    private static String atomId(String input) {
        return "urn:uuid:" + UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String etag(byte[] xml) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(xml);
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ENGLISH, "%.1f", value);
    }

    private static String plainLabel(String value) {
        return value.replace('_', ' ');
    }

    private static String normalizeLocationId(String rawLocationId) {
        if (rawLocationId == null) {
            throw new InvalidOpportunitySearchRequestException("locationId is required.");
        }
        String locationId = rawLocationId.strip();
        if (locationId.isBlank()) {
            throw new InvalidOpportunitySearchRequestException("locationId must be non-empty.");
        }
        if (containsUnsupportedControlCharacter(locationId)) {
            throw new InvalidOpportunitySearchRequestException(
                    "locationId contains unsupported control characters.");
        }
        if (locationId.codePointCount(0, locationId.length()) > MAX_LOCATION_ID_CODE_POINTS) {
            throw new InvalidOpportunitySearchRequestException(
                    "locationId must be 100 characters or fewer.");
        }
        return locationId;
    }

    /*
     * containsUnsupportedControlCharacter validates the locationId before it becomes a
     * cache key or appears in the Atom feed.
     *
     * value.codePoints() correctly walks Unicode characters, including characters
     * represented by two Java char values. anyMatch(...) returns true as soon as it finds
     * a forbidden character. Character.isISOControl(...) catches ordinary control
     * characters such as nulls, newlines, tabs, and escape characters.
     *
     * 0x061C, 0x200E, 0x200F, 0x202A–0x202E, and 0x2066–0x2069 are invisible
     * bidirectional-text controls. They can change how surrounding text is displayed,
     * making one ID look like another or appear reordered.
     *
     * For example, an attacker could insert an invisible right-to-left override into an
     * otherwise normal-looking location ID. The actual value, displayed value, cache key,
     * and logged value could then disagree.
     *
     * The method does not clean the value. normalizeLocationId rejects it, and the
     * controller returns 400 invalid_request.
     *
     * This is not specifically an Atom/XML sanitizer. It copies the existing locationId
     * rule from OpportunitySearchService and ProductRequestParser, so /feeds/atom
     * validates IDs consistently with the other endpoints.
     */
    private static boolean containsUnsupportedControlCharacter(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint)
                        || codePoint == 0x061C
                        || codePoint == 0x200E
                        || codePoint == 0x200F
                        || codePoint >= 0x202A && codePoint <= 0x202E
                        || codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    record AtomFeed(byte[] xml, String etag) {
    }

    static final class FeedFailure extends RuntimeException {
        private final HttpStatus httpStatus;
        private final String status;

        private FeedFailure(HttpStatus httpStatus, String status, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.status = status;
        }

        static FeedFailure locationNotFound() {
            return new FeedFailure(
                    HttpStatus.NOT_FOUND,
                    "location_not_found",
                    "No matching location found.");
        }

        static FeedFailure temporarilyUnavailable() {
            return new FeedFailure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "temporarily_unavailable",
                    "Moon opportunities are temporarily unavailable.");
        }

        HttpStatus httpStatus() {
            return httpStatus;
        }

        String status() {
            return status;
        }
    }

    private static final class FeedState {
        private volatile FeedSnapshot snapshot;
        private CompletableFuture<FeedSnapshot> refresh;

        FeedSnapshot current(
                Instant now,
                Function<FeedSnapshot, FeedSnapshot> loader
        ) {
            FeedSnapshot visible = snapshot;
            if (visible != null && now.isBefore(visible.staleAt())) {
                return visible;
            }

            CompletableFuture<FeedSnapshot> work;
            FeedSnapshot previous = null;
            boolean ownsRefresh = false;
            synchronized (this) {
                visible = snapshot;
                if (visible != null && now.isBefore(visible.staleAt())) {
                    return visible;
                }
                if (refresh == null) {
                    refresh = new CompletableFuture<>();
                    previous = visible;
                    ownsRefresh = true;
                }
                work = refresh;
            }

            if (ownsRefresh) {
                completeRefresh(work, loader, previous);
            }
            return completedValue(work);
        }

        private void completeRefresh(
                CompletableFuture<FeedSnapshot> work,
                Function<FeedSnapshot, FeedSnapshot> loader,
                FeedSnapshot previous
        ) {
            try {
                FeedSnapshot next = loader.apply(previous);
                synchronized (this) {
                    snapshot = next;
                    work.complete(next);
                    refresh = null;
                }
            } catch (Throwable failure) {
                synchronized (this) {
                    work.completeExceptionally(failure);
                    refresh = null;
                }
            }
        }

        private static FeedSnapshot completedValue(CompletableFuture<FeedSnapshot> work) {
            try {
                return work.join();
            } catch (CompletionException ex) {
                if (ex.getCause() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (ex.getCause() instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Feed refresh failed.", ex.getCause());
            }
        }
    }

    private record FeedSnapshot(
            FeedMetadata metadata,
            List<EntrySnapshot> entries,
            String feedUpdated,
            Instant staleAt,
            byte[] xml,
            String etag
    ) {
    }

    private record FeedMetadata(String title, String id, String author, String selfUrl) {
    }

    private record EntrySnapshot(DisplayedEntry displayed, String updated) {
    }

    private record DisplayedEntry(
            String id,
            String title,
            String summary,
            String alternateUrl,
            String suggestedAt
    ) {
    }
}
