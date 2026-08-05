package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimePreference;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ScoredWindow;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.service.OpportunityService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityIntervalCoalescingTest {
    private static final OpportunityHardFilter FILTER = new OpportunityHardFilter();
    private static final Location PRAGUE = Locations.PRAGUE;
    private static final Instant BASE = Instant.parse("2026-08-05T03:00:00Z");

    @Test
    void groupsInclusiveTenMinuteGapsTransitivelyAndReusesMemberData() {
        Function<Instant, MoonSample> samples = instant -> {
            boolean best = instant.equals(at(25));
            return sample(instant, best ? 5.0 : 30.0, best ? -0.5 : 8.0, 50.0, 220.0);
        };
        Instant passEnd = at(90);
        MoonWindow firstSource = window(BASE, passEnd, BASE, at(45), at(22), "source", samples);
        MoonWindow secondSource = window(BASE, passEnd, at(45), passEnd, at(67), "source", samples);
        List<FilteredWindowCoalescer.SourceWindow> members = List.of(
                member(firstSource, at(20), at(36), at(25), "moonset_low", samples),
                member(secondSource, at(45), at(60), at(50), "moonset_context", samples),
                member(firstSource, BASE, at(10), at(5), "moonrise_context", samples));

        List<MoonWindow> result = FilteredWindowCoalescer.coalesce(members);

        assertEquals(1, result.size());
        MoonWindow combined = result.getFirst();
        assertEquals(BASE, combined.startsAt());
        assertEquals(at(60), combined.endsAt());
        assertEquals(at(25), combined.suggested().instant());
        assertEquals("moonset_low", combined.kind());
        assertEquals(List.of(BASE, at(5), at(10), at(20), at(25), at(36), at(45), at(50), at(60)),
                combined.pathSamples().stream().map(MoonSample::instant).toList());
    }

    @Test
    void keepsLongNaturalSourceAndDifferentPassGapsSeparate() {
        Function<Instant, MoonSample> samples = constantSamples();
        MoonWindow sharedSource = window(BASE, at(60), BASE, at(60), at(30), "source", samples);
        assertEquals(2, FilteredWindowCoalescer.coalesce(List.of(
                member(sharedSource, BASE, at(10), at(5), "moonrise_low", samples),
                member(sharedSource, at(21), at(30), at(25), "moonrise_low", samples))).size());

        MoonWindow sourceBeforeGap = window(BASE, at(60), BASE, at(30), at(15), "source", samples);
        MoonWindow sourceAfterGap = window(BASE, at(60), at(35), at(60), at(47), "source", samples);
        assertEquals(2, FilteredWindowCoalescer.coalesce(List.of(
                member(sourceBeforeGap, at(10), at(30), at(20), "moonrise_low", samples),
                member(sourceAfterGap, at(35), at(50), at(40), "moonset_low", samples))).size());

        MoonWindow firstPass = window(BASE, at(30), BASE, at(30), at(15), "source", samples);
        MoonWindow secondPass = window(at(30), at(60), at(30), at(60), at(45), "source", samples);
        assertEquals(2, FilteredWindowCoalescer.coalesce(List.of(
                member(firstPass, at(10), at(30), at(20), "moonrise_low", samples),
                member(secondPass, at(30), at(50), at(40), "moonset_low", samples))).size());
    }

    @Test
    void prefersAVisibleMemberAndPreservesAnAllInvisibleRejection() {
        Function<Instant, MoonSample> mixedSamples = instant -> instant.equals(at(5))
                ? sample(instant, 5.0, 5.0, 0.5, 100.0)
                : sample(instant, 30.0, 5.0, 50.0, 220.0);
        MoonWindow source = window(BASE, at(40), BASE, at(40), at(20), "source", mixedSamples);
        MoonWindow visible = FilteredWindowCoalescer.coalesce(List.of(
                member(source, BASE, at(10), at(5), "moonrise_low", mixedSamples),
                member(source, at(15), at(30), at(20), "moonset_context", mixedSamples))).getFirst();

        assertEquals(at(20), visible.suggested().instant());
        assertEquals("moonset_context", visible.kind());
        assertTrue(ScoringModel.ordinaryVisibilityRejectionReason(visible).isEmpty());

        Function<Instant, MoonSample> invisibleSamples = instant ->
                sample(instant, 5.0, 5.0, 0.5, 100.0);
        MoonWindow invisibleSource = window(
                BASE, at(40), BASE, at(40), at(20), "source", invisibleSamples);
        MoonWindow invisible = FilteredWindowCoalescer.coalesce(List.of(
                member(invisibleSource, BASE, at(10), at(5), "moonrise_low", invisibleSamples),
                member(invisibleSource, at(15), at(30), at(20), "moonset_low", invisibleSamples)))
                .getFirst();
        assertTrue(ScoringModel.ordinaryVisibilityRejectionReason(invisible).isPresent());
    }

    @Test
    void appliesLiveEligibilityBeforeGroupingAndLeavesInactiveWindowsUnchanged() {
        Function<Instant, MoonSample> samples = instant -> {
            long minute = Duration.between(BASE, instant).toMinutes();
            boolean matches = minute <= 10 || minute >= 18 && minute <= 30;
            return sample(instant, matches ? 5.0 : 20.0, 8.0, 50.0, 220.0);
        };
        MoonWindow source = window(BASE, at(60), BASE, at(60), at(30), "source", samples);
        OpportunityPreferences lowAltitude = new OpportunityPreferences(
                1, new AltitudeRange(0.0, 10.0), null, null, null, null);

        MoonWindow live = filter(List.of(source), samples, lowAltitude, at(20)).windows().getFirst();
        assertTrue(live.startsAt().isBefore(at(19)));
        assertFalse(live.suggested().instant().isBefore(at(20)));
        assertEquals(1, filter(List.of(source), samples, lowAltitude, at(15)).windows().size());
        assertTrue(filter(List.of(source), samples, lowAltitude, at(31)).windows().isEmpty());

        List<MoonWindow> natural = List.of(
                window(BASE, at(60), BASE, at(30), at(15), "moonrise_low", samples),
                window(BASE, at(60), at(30), at(60), at(45), "moonset_low", samples));
        OpportunityHardFilter.Result inactive = filter(
                natural, samples, OpportunityPreferences.none(), BASE);
        assertEquals(natural, inactive.windows());
        assertSame(natural.getFirst(), inactive.windows().getFirst());
    }

    @Test
    void groupsThePraguePeakBoundaryRegression() {
        PrototypeConfig config = new PrototypeConfig(
                PRAGUE, LocalDate.parse("2026-07-30"), 7, 90.0, 10);
        OpportunityPreferences preferences = new OpportunityPreferences(
                1, null, null,
                new TimePreference(TimeMode.LIGHT_BUCKET, null,
                        EnumSet.of(AmbientLight.DAYLIGHT, AmbientLight.GOLDEN_HOUR)),
                EnumSet.complementOf(EnumSet.of(NamedPhase.NEW_MOON)),
                List.of(new DegreeRange(247.5, 292.5)));

        OpportunityService.PreferenceEvaluation evaluation = new OpportunityService().evaluate(
                config, ignored -> WeatherFixture.PRAGUE_PARTLY_CLOUDY, preferences, config.start());
        ScoredWindow affected = evaluation.result().opportunities().stream()
                .filter(item -> between(item.window().startsAt(), at(31), at(35)))
                .findFirst().orElseThrow();
        MoonWindow combined = affected.window();

        assertEquals(1, evaluation.result().opportunities().stream()
                .filter(item -> item.window().passId().equals(combined.passId())).count());
        assertTrue(combined.endsAt().isAfter(at(57)));
        assertEquals(ScoringModel.scoreWindow(combined, affected.weather()), affected.components());
    }

    private static OpportunityHardFilter.Result filter(
            List<MoonWindow> windows,
            Function<Instant, MoonSample> samples,
            OpportunityPreferences preferences,
            Instant notBefore
    ) {
        return FILTER.filter(PRAGUE, windows, samples::apply, ignored -> 0.25, preferences, notBefore);
    }

    private static FilteredWindowCoalescer.SourceWindow member(
            MoonWindow source,
            Instant startsAt,
            Instant endsAt,
            Instant suggestedAt,
            String kind,
            Function<Instant, MoonSample> samples
    ) {
        return new FilteredWindowCoalescer.SourceWindow(source, window(
                source.passStartsAt(), source.passEndsAt(), startsAt, endsAt, suggestedAt, kind, samples));
    }

    private static MoonWindow window(
            Instant passStartsAt,
            Instant passEndsAt,
            Instant startsAt,
            Instant endsAt,
            Instant suggestedAt,
            String kind,
            Function<Instant, MoonSample> samples
    ) {
        return new MoonWindow(
                PRAGUE, kind, passStartsAt, passEndsAt,
                startsAt, samples.apply(startsAt), samples.apply(suggestedAt),
                samples.apply(endsAt), endsAt,
                List.of(samples.apply(passStartsAt), samples.apply(passEndsAt)),
                List.of(samples.apply(startsAt), samples.apply(suggestedAt), samples.apply(endsAt)));
    }

    private static Function<Instant, MoonSample> constantSamples() {
        return instant -> sample(instant, 5.0, 8.0, 50.0, 220.0);
    }

    private static MoonSample sample(
            Instant instant,
            double moonAltitude,
            double sunAltitude,
            double illumination,
            double sunAzimuth
    ) {
        return new MoonSample(
                instant, moonAltitude, 100.0, illumination, 270.0, null, sunAltitude, sunAzimuth);
    }

    private static Instant at(long minutes) {
        return BASE.plus(Duration.ofMinutes(minutes));
    }

    private static boolean between(Instant value, Instant lower, Instant upper) {
        return !value.isBefore(lower) && value.isBefore(upper);
    }
}
