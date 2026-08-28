package dev.moonservice.backend.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Controller-facing coordinator for public Atom feeds. The classes form a
 * composition pipeline, not an inheritance hierarchy:
 * {@link AtomFeedController} calls this service, this service asks
 * {@link AtomFeedDocumentRenderer} for stable display snapshots and XML, and
 * that renderer uses {@link AtomEntryPreviewRenderer} for entry images. The
 * preview renderer delegates Moon discs to {@link AtomMoonRenderer} and
 * weather artwork to {@link AtomWeatherRenderer}.
 *
 * <p>This class owns the parts that are not presentation: opportunity search,
 * the one-hour freshness window, per-cached-feed single-flight refresh
 * work, stable Atom update times, strong ETags, and the bounded process cache.
 */
@Service
final class AtomFeedService {
    private static final int MAX_LOCATION_ID_CODE_POINTS = 100;
    private static final int MAX_FEED_ENTRIES = 10;
    private static final int MIN_FILTERED_CACHE_WEIGHT_BYTES = 96 * 1024;
    /* Object overhead is not part of this limit. */
    private static final long MAX_CACHE_WEIGHT_BYTES = 96L * 1024 * 1024;
    /* A stale entry triggers a new search/render; it is not deleted on this timer. */
    private static final Duration FRESHNESS = Duration.ofHours(1);

    private final OpportunitySearchService opportunitySearchService;
    private final Clock clock;
    private final Cache<FeedKey, FeedState> states = Caffeine.newBuilder()
            .<FeedKey, FeedState>weigher((key, state) -> key.filtered()
                    ? Math.max(state.cachedXmlBytes(), MIN_FILTERED_CACHE_WEIGHT_BYTES)
                    : state.cachedXmlBytes())
            .maximumWeight(MAX_CACHE_WEIGHT_BYTES)
            .build();

    AtomFeedService(OpportunitySearchService opportunitySearchService, Clock clock) {
        this.opportunitySearchService = Objects.requireNonNull(opportunitySearchService, "opportunitySearchService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    AtomFeed feed(String rawLocationId) {
        return feed(FeedRequest.unfiltered(normalizeLocationId(rawLocationId)));
    }

    AtomFeed feed(PublicPreferenceQuery.CalendarRequest request) {
        Objects.requireNonNull(request, "request");
        return feed(FeedRequest.from(request));
    }

    private AtomFeed feed(FeedRequest request) {
        FeedKey key = request.key();
        FeedState state = states.get(key, ignored -> new FeedState());
        FeedSnapshot snapshot;
        try {
            snapshot = state.current(
                    clock.instant(),
                    previous -> refresh(request, previous));
        } catch (RuntimeException | Error failure) {
            // A first failed load has no useful cached value, so do not retain its zero-weight state.
            if (state.cachedXmlBytes() == 0) {
                states.asMap().remove(key, state);
            }
            throw failure;
        }
        /*
         * FeedState is mutable. Inserting it again after current() makes
         * Caffeine weigh its current XML rather than the zero-byte state first
         * inserted for this feed key. It works whether this caller performed
         * the refresh or waited for another caller's refresh.
         */
        states.put(key, state);
        return new AtomFeed(snapshot.xml(), snapshot.etag());
    }

    private FeedSnapshot refresh(
            FeedRequest request,
            FeedSnapshot previous
    ) {
        /* Use the shared canonical seven-day search defaults, with SOONEST ordering for the feed. */
        OpportunityResponse response;
        try {
            if (request.preferences() != null) {
                response = opportunitySearchService.search(
                        null,
                        request.locationId(),
                        OpportunitySearchRequest.Order.SOONEST,
                        request.preferences(),
                        request.ignoredFields().paths(),
                        request.ignoredFields().count(),
                        request.weatherRanking().scoringValue());
            } else if (request.weatherRanking() != ProductWeatherRanking.BALANCED) {
                response = opportunitySearchService.search(
                        null,
                        request.locationId(),
                        OpportunitySearchRequest.Order.SOONEST,
                        request.weatherRanking().scoringValue());
            } else {
                response = opportunitySearchService.search(
                        null,
                        request.locationId(),
                        OpportunitySearchRequest.Order.SOONEST);
            }
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
        AtomFeedDocumentRenderer.FeedMetadata metadata = request.key().filtered()
                ? AtomFeedDocumentRenderer.filteredMetadata(
                        success.location(), request.key().canonicalSelfPath())
                : AtomFeedDocumentRenderer.metadata(success.location());
        List<AtomFeedDocumentRenderer.DisplayedEntry> displayedEntries = success.opportunities().stream()
                .map(opportunity -> AtomFeedDocumentRenderer.displayedEntry(success.location(), opportunity))
                .sorted(Comparator
                        .comparing((AtomFeedDocumentRenderer.DisplayedEntry entry) ->
                                Instant.parse(entry.suggestedAt()))
                        .thenComparing(AtomFeedDocumentRenderer.DisplayedEntry::id))
                .limit(MAX_FEED_ENTRIES)
                .toList();

        Map<String, AtomFeedDocumentRenderer.EntrySnapshot> previousEntries = previous == null
                ? Map.of()
                : previous.entries().stream().collect(Collectors.toMap(
                        entry -> entry.displayed().id(), Function.identity()));
        // Match by stable Atom ID so unchanged surviving entries keep their own updated values.
        List<AtomFeedDocumentRenderer.EntrySnapshot> entries = displayedEntries.stream()
                .map(displayed -> entrySnapshot(displayed, previousEntries.get(displayed.id()), generatedAt))
                .toList();
        // The feed-level timestamp advances only when its visible metadata or ordered entries change.
        String feedUpdated = previous == null
                || !metadata.equals(previous.metadata())
                || !entries.equals(previous.entries())
                ? generatedAt
                : previous.feedUpdated();

        byte[] xml = AtomFeedDocumentRenderer.render(metadata, entries, feedUpdated);
        return new FeedSnapshot(
                metadata,
                entries,
                feedUpdated,
                clock.instant().plus(FRESHNESS),
                xml,
                etag(xml));
    }

    private static AtomFeedDocumentRenderer.EntrySnapshot entrySnapshot(
            AtomFeedDocumentRenderer.DisplayedEntry displayed,
            AtomFeedDocumentRenderer.EntrySnapshot previous,
            String generatedAt
    ) {
        /* A stable entry ID plus equal visible values keeps that entry's old Atom timestamp. */
        String updated = previous != null && previous.displayed().equals(displayed)
                ? previous.updated()
                : generatedAt;
        return new AtomFeedDocumentRenderer.EntrySnapshot(displayed, updated);
    }

    private static String etag(byte[] xml) {
        /* Hash the exact serialized representation so this is a strong ETag, not a freshness token. */
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(xml);
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
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

    private record FeedKey(String canonicalSelfPath, boolean filtered) {
        private FeedKey {
            Objects.requireNonNull(canonicalSelfPath, "canonicalSelfPath");
        }
    }

    private record FeedRequest(
            FeedKey key,
            String locationId,
            ProductWeatherRanking weatherRanking,
            OpportunityPreferences preferences,
            ProductRequestParser.IgnoredFields ignoredFields
    ) {
        private FeedRequest {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(locationId, "locationId");
            Objects.requireNonNull(weatherRanking, "weatherRanking");
            Objects.requireNonNull(ignoredFields, "ignoredFields");
        }

        static FeedRequest unfiltered(String locationId) {
            ProductWeatherRanking weatherRanking = ProductWeatherRanking.BALANCED;
            String selfPath = canonicalSelfPath(locationId, weatherRanking, null);
            return new FeedRequest(
                    new FeedKey(selfPath, false),
                    locationId,
                    weatherRanking,
                    null,
                    new ProductRequestParser.IgnoredFields(List.of(), 0));
        }

        static FeedRequest from(PublicPreferenceQuery.CalendarRequest request) {
            String locationId = normalizeLocationId(request.locationId());
            ProductWeatherRanking weatherRanking = request.weatherRanking() == null
                    ? ProductWeatherRanking.BALANCED
                    : request.weatherRanking();
            OpportunityPreferences canonicalPreferences = request.preferences();
            if (canonicalPreferences != null && !canonicalPreferences.active()) {
                canonicalPreferences = null;
            }
            String selfPath = canonicalSelfPath(locationId, weatherRanking, canonicalPreferences);
            boolean filtered = weatherRanking != ProductWeatherRanking.BALANCED
                    || canonicalPreferences != null;
            return new FeedRequest(
                    new FeedKey(selfPath, filtered),
                    locationId,
                    weatherRanking,
                    canonicalPreferences,
                    request.ignoredFields());
        }

        private static String canonicalSelfPath(
                String locationId,
                ProductWeatherRanking weatherRanking,
                OpportunityPreferences preferences
        ) {
            return "/feeds/atom" + PublicPreferenceQuery.calendarQuery(
                    locationId,
                    OpportunitySearchRequest.Order.BEST_MATCH,
                    weatherRanking,
                    preferences);
        }
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

    /**
     * Mutable state shared by callers for one canonical feed identity. A volatile
     * snapshot makes fresh reads lock-free. When stale, one caller owns the
     * load and the others join the same future; different feed identities use
     * different states and can refresh in parallel.
     */
    private static final class FeedState {
        private volatile FeedSnapshot snapshot;
        private CompletableFuture<FeedSnapshot> refresh;

        int cachedXmlBytes() {
            FeedSnapshot visible = snapshot;
            return visible == null ? 0 : visible.xml().length;
        }

        FeedSnapshot current(
                Instant now,
                Function<FeedSnapshot, FeedSnapshot> loader
        ) {
            // Fast path for the common fresh-cache case.
            FeedSnapshot visible = snapshot;
            if (visible != null && now.isBefore(visible.staleAt())) {
                return visible;
            }

            CompletableFuture<FeedSnapshot> work;
            FeedSnapshot previous = null;
            boolean ownsRefresh = false;
            synchronized (this) {
                // Recheck after taking the monitor because another caller may have just refreshed.
                visible = snapshot;
                if (visible != null && now.isBefore(visible.staleAt())) {
                    return visible;
                }
                if (refresh == null) {
                    // Publish the future before doing slow provider work outside the monitor.
                    refresh = new CompletableFuture<>();
                    previous = visible;
                    ownsRefresh = true;
                }
                work = refresh;
            }

            if (ownsRefresh) {
                completeRefresh(work, loader, previous);
            }
            // Both the owner and followers observe the same result or failure.
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
                    // Install the complete immutable snapshot before releasing waiting callers.
                    snapshot = next;
                    work.complete(next);
                    refresh = null;
                }
            } catch (Throwable failure) {
                synchronized (this) {
                    // Keep any previous snapshot, but let every current caller see this failure.
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

    /** Complete snapshot bundle used for visible-change comparison, cache delivery, and ETag response. */
    private record FeedSnapshot(
            AtomFeedDocumentRenderer.FeedMetadata metadata,
            List<AtomFeedDocumentRenderer.EntrySnapshot> entries,
            String feedUpdated,
            Instant staleAt,
            byte[] xml,
            String etag
    ) {
    }
}
