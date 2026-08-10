package dev.moonservice.scoringprototype.service;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ComponentScores;
import dev.moonservice.scoringprototype.scoring.ScoredWindow;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import dev.moonservice.scoringprototype.window.MoonWindow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.moonservice.scoringprototype.service.OpportunityService.ResultOrder.BEST_MATCH;
import static dev.moonservice.scoringprototype.service.OpportunityService.ResultOrder.SOONEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityServiceOrderTest {
    private static final LocalDate START = LocalDate.parse("2026-06-29");
    private static final WeatherFixture WEATHER = WeatherFixture.PRAGUE_PARTLY_CLOUDY;
    private static final WeatherFixture CLEAR_WEATHER = new WeatherFixture(
            0, 0, 0, 0, 5, 0.0, 20000, 2, 1.0);
    private static final OpportunityService.WindowAdjustment KEEP_WINDOW =
            (window, samples) -> Optional.of(window);
    private static final OpportunityService.WindowAdjustment NORMALIZE_SUGGESTED =
            (window, samples) -> Optional.of(withSuggested(window, sample(window.suggested().instant())));

    @Test
    void appliesSoonestAcrossEveryEligibleWindowBeforeTheLimit() {
        OpportunityService service = new OpportunityService();
        PrototypeResult bestMatch = service.evaluate(
                config(100), ignored -> WEATHER, KEEP_WINDOW, BEST_MATCH);
        PrototypeResult soonest = service.evaluate(
                config(100), ignored -> WEATHER, KEEP_WINDOW, SOONEST);

        List<ScoredWindow> expected = new ArrayList<>(bestMatch.opportunities());
        expected.sort(Comparator
                .comparing((ScoredWindow item) -> item.window().suggested().instant())
                .thenComparing(Comparator.comparingInt(
                        (ScoredWindow item) -> item.components().total()).reversed())
                .thenComparing(item -> item.window().id()));

        assertTrue(soonest.opportunities().size() > 1);
        assertEquals(expected, soonest.opportunities());
        assertNotEquals(bestMatch.opportunities(), soonest.opportunities());

        PrototypeResult limited = service.evaluate(
                config(1), ignored -> WEATHER, KEEP_WINDOW, SOONEST);

        assertEquals(List.of(soonest.opportunities().getFirst()), limited.opportunities());
    }

    @Test
    void weatherRankingChangesFinalScoreOrderWithoutChangingOrdinaryWindows() {
        OpportunityService service = new OpportunityService();
        PrototypeResult omittedBalanced = service.evaluate(
                config(100), sourceOrderWeather(), NORMALIZE_SUGGESTED, BEST_MATCH);
        PrototypeResult explicitBalanced = service.evaluate(
                config(100),
                sourceOrderWeather(),
                NORMALIZE_SUGGESTED,
                BEST_MATCH,
                WeatherRanking.BALANCED);
        PrototypeResult preferClear = service.evaluate(
                config(100),
                sourceOrderWeather(),
                NORMALIZE_SUGGESTED,
                BEST_MATCH,
                WeatherRanking.PREFER_CLEAR);

        assertEquals(omittedBalanced, explicitBalanced);
        assertTrue(explicitBalanced.opportunities().size() > 1);
        assertEquals(35, explicitBalanced.opportunities().getFirst().weather().cloudCoverPercent());
        assertEquals(0, preferClear.opportunities().getFirst().weather().cloudCoverPercent());
        assertDescendingScores(explicitBalanced.opportunities());
        assertDescendingScores(preferClear.opportunities());
        assertEquals(windowsById(explicitBalanced), windowsById(preferClear));
    }

    @Test
    void ignoreWeatherFlowsThroughHardPreferencesWithoutChangingFilteredWindows() {
        OpportunityService service = new OpportunityService();
        PrototypeConfig fullConfig = config(100);
        OpportunityPreferences preferences = new OpportunityPreferences(
                1, new AltitudeRange(10.0, 12.0), null, null, null, null);
        OpportunityService.PreferenceEvaluation balanced = service.evaluate(
                fullConfig,
                ignored -> WEATHER,
                preferences,
                fullConfig.start(),
                BEST_MATCH,
                WeatherRanking.BALANCED);
        OpportunityService.PreferenceEvaluation ignoreWeather = service.evaluate(
                fullConfig,
                ignored -> WEATHER,
                preferences,
                fullConfig.start(),
                BEST_MATCH,
                WeatherRanking.IGNORE_WEATHER);

        assertTrue(balanced.result().opportunities().size() > 1);
        assertEquals(
                balanced.appliedPreferenceVersion(), ignoreWeather.appliedPreferenceVersion());
        assertEquals(
                balanced.normalizedActiveFilters(), ignoreWeather.normalizedActiveFilters());
        assertEquals(balanced.excludedSampleCount(), ignoreWeather.excludedSampleCount());
        assertEquals(windowsById(balanced.result()), windowsById(ignoreWeather.result()));
        assertTrue(balanced.result().opportunities().stream()
                .allMatch(item -> item.components().weatherFit() != null));
        assertTrue(ignoreWeather.result().opportunities().stream().allMatch(item ->
                item.components().weatherFit() == null
                        && item.components().forecastConfidence() == null
                        && item.components().componentMaximum() == 70));
        assertDescendingScores(ignoreWeather.result().opportunities());
    }

    @Test
    void usesEverySoonestTieLevelAndStableSourceOrder() throws ReflectiveOperationException {
        Comparator<ScoredWindow> comparator = comparator(SOONEST);
        Instant first = Instant.parse("2026-06-29T20:00:00Z");
        ScoredWindow earlierLowScore = scored("a", "earlier", first, 10);
        ScoredWindow laterHighScore = scored("a", "later", first.plusSeconds(60), 90);
        ScoredWindow sameTimeHighScore = scored("c", "high", first, 80);
        ScoredWindow sameTimeLowScore = scored("c", "low", first, 20);
        ScoredWindow smallerId = scored("a", "small-id", first, 50);
        ScoredWindow largerId = scored("b", "large-id", first, 50);
        ScoredWindow stableFirst = scored("same", "first", first, 50);
        ScoredWindow stableSecond = scored("same", "second", first, 50);

        assertTrue(comparator.compare(earlierLowScore, laterHighScore) < 0);
        assertTrue(comparator.compare(sameTimeHighScore, sameTimeLowScore) < 0);
        assertTrue(comparator.compare(smallerId, largerId) < 0);
        assertEquals(0, comparator.compare(stableFirst, stableSecond));

        List<ScoredWindow> stable = new ArrayList<>(List.of(stableSecond, stableFirst));
        stable.sort(comparator);
        assertEquals(List.of(stableSecond, stableFirst), stable);
    }

    @Test
    void finalizesInactiveLiveWindowsBeforeSoonestOrdering() {
        OpportunityService service = new OpportunityService();
        PrototypeConfig config = config(100);
        PrototypeResult original = service.evaluate(config);
        ScoredWindow source = original.opportunities().stream()
                .filter(item -> Duration.between(
                        item.window().suggested().instant(), item.window().endsAt()).toMinutes() >= 10)
                .findFirst()
                .orElseThrow();
        Instant notBefore = source.window().suggested().instant().plus(Duration.ofMinutes(5));

        PrototypeResult live = service.evaluate(
                config, ignored -> WEATHER, OpportunityPreferences.none(), notBefore, SOONEST).result();
        MoonWindow retained = live.opportunities().stream()
                .map(ScoredWindow::window)
                .filter(window -> window.startsAt().equals(source.window().startsAt()))
                .findFirst()
                .orElseThrow();

        assertTrue(retained.startsAt().isBefore(notBefore));
        assertFalse(retained.suggested().instant().isBefore(notBefore));
        assertNotEquals(source.window().suggested().instant(), retained.suggested().instant());

        Instant completedAt = source.window().endsAt();
        PrototypeResult afterCompletion = service.evaluate(
                config, ignored -> WEATHER, OpportunityPreferences.none(), completedAt, SOONEST).result();
        assertTrue(afterCompletion.opportunities().stream()
                .map(ScoredWindow::window)
                .allMatch(window -> window.endsAt().isAfter(completedAt)));
        assertTrue(afterCompletion.opportunities().stream()
                .map(ScoredWindow::window)
                .noneMatch(window -> window.startsAt().equals(source.window().startsAt())));
    }

    @Test
    void filtersCompleteNaturalWindowsBeforeFinalizingOrderingAndLimitingActiveFragments() {
        OpportunityService service = new OpportunityService();
        PrototypeConfig fullConfig = config(100);
        PrototypeConfig limitedConfig = config(1);
        OpportunityPreferences preferences = new OpportunityPreferences(
                1, new AltitudeRange(10.0, 12.0), null, null, null, null);
        PrototypeResult natural = service.evaluate(
                fullConfig, ignored -> WEATHER, KEEP_WINDOW, SOONEST);
        PrototypeResult initial = service.evaluate(
                fullConfig, ignored -> WEATHER, preferences, fullConfig.start(), SOONEST).result();
        ScoredWindow source = initial.opportunities().stream()
                .filter(item -> Duration.between(
                        item.window().suggested().instant(), item.window().endsAt()).toMinutes() >= 10)
                .findFirst()
                .orElseThrow();
        Instant notBefore = source.window().suggested().instant().plus(Duration.ofMinutes(5));

        PrototypeResult full = service.evaluate(
                fullConfig, ignored -> WEATHER, preferences, notBefore, SOONEST).result();
        PrototypeResult limited = service.evaluate(
                limitedConfig, ignored -> WEATHER, preferences, notBefore, SOONEST).result();
        MoonWindow retained = full.opportunities().stream()
                .map(ScoredWindow::window)
                .filter(window -> window.passId().equals(source.window().passId())
                        && window.startsAt().equals(source.window().startsAt()))
                .findFirst()
                .orElseThrow();

        assertEquals(natural.candidateWindowsEvaluated(), full.candidateWindowsEvaluated());
        assertTrue(retained.startsAt().isBefore(notBefore));
        assertFalse(retained.suggested().instant().isBefore(notBefore));
        assertEquals(source.window().endsAt(), retained.endsAt());
        assertNotEquals(source.window().suggested().instant(), retained.suggested().instant());
        assertTrue(full.opportunities().size() > 1);
        assertChronological(full.opportunities());
        assertEquals(List.of(full.opportunities().getFirst()), limited.opportunities());
        assertTrue(full.opportunities().stream().allMatch(item ->
                item.window().suggested().moonAltitudeDegrees() >= 10.0
                        && item.window().suggested().moonAltitudeDegrees() <= 12.0));
    }

    @SuppressWarnings("unchecked")
    private static Comparator<ScoredWindow> comparator(
            OpportunityService.ResultOrder order
    ) throws ReflectiveOperationException {
        Method method = OpportunityService.class.getDeclaredMethod(
                "comparator", OpportunityService.ResultOrder.class);
        method.setAccessible(true);
        return (Comparator<ScoredWindow>) method.invoke(null, order);
    }

    private static PrototypeConfig config(int limit) {
        return new PrototypeConfig(Locations.PRAGUE, START, 7, 12.0, limit);
    }

    private static ScoredWindow scored(String slug, String kind, Instant suggestedAt, int score) {
        Location location = new Location(
                slug, "real_location", "test:" + slug, slug, 0.0, 0.0, 0.0, "UTC", "ZZ");
        Instant startsAt = suggestedAt.minusSeconds(60);
        Instant endsAt = suggestedAt.plusSeconds(60);
        MoonSample start = sample(startsAt);
        MoonSample suggested = sample(suggestedAt);
        MoonSample end = sample(endsAt);
        MoonWindow window = new MoonWindow(
                location,
                kind,
                startsAt.minusSeconds(60),
                endsAt.plusSeconds(60),
                startsAt,
                start,
                suggested,
                end,
                endsAt,
                List.of(start, suggested, end),
                List.of(start, suggested, end));
        return new ScoredWindow(
                window,
                WEATHER,
                new ComponentScores(score, 0, 0, 0, 0, 100, List.of()));
    }

    private static MoonSample sample(Instant instant) {
        return new MoonSample(instant, 5.0, 100.0, 50.0, 90.0, 0.0, -8.0, 200.0);
    }

    private static MoonWindow withSuggested(MoonWindow window, MoonSample suggested) {
        return new MoonWindow(
                window.location(),
                window.kind(),
                window.passStartsAt(),
                window.passEndsAt(),
                window.startsAt(),
                window.start(),
                suggested,
                window.end(),
                window.endsAt(),
                window.passPathSamples(),
                window.pathSamples());
    }

    private static WindowWeatherProvider sourceOrderWeather() {
        AtomicInteger index = new AtomicInteger();
        return ignored -> index.getAndIncrement() == 0 ? CLEAR_WEATHER : WEATHER;
    }

    private static List<MoonWindow> windowsById(PrototypeResult result) {
        return result.opportunities().stream()
                .map(ScoredWindow::window)
                .sorted(Comparator.comparing(MoonWindow::id))
                .toList();
    }

    private static void assertDescendingScores(List<ScoredWindow> opportunities) {
        for (int index = 1; index < opportunities.size(); index++) {
            assertTrue(opportunities.get(index - 1).components().total()
                    >= opportunities.get(index).components().total());
        }
    }

    private static void assertChronological(List<ScoredWindow> opportunities) {
        for (int index = 1; index < opportunities.size(); index++) {
            Instant previous = opportunities.get(index - 1).window().suggested().instant();
            Instant current = opportunities.get(index).window().suggested().instant();
            assertFalse(current.isBefore(previous));
        }
    }
}
