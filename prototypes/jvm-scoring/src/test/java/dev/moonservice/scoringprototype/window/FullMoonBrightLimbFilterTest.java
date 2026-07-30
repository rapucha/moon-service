package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullMoonBrightLimbFilterTest {
    private static final Location PRAGUE = Locations.PRAGUE;
    private static final Instant INSTANT = Instant.parse("2026-06-29T20:00:00Z");
    private static final DegreeRange RIGHT = new DegreeRange(80.0, 100.0);
    private static final List<MoonSample> FULL_BUCKET = List.of(
            sample(157.5), sample(180.0), sample(202.5 - 1.0e-6));
    private static final MoonSample WAXING_GIBBOUS = sample(135.0);

    @Test
    void fullMoonNeverMatchesAnActiveBrightLimbFilter() {
        for (MoonSample sample : FULL_BUCKET) {
            assertFalse(matches(preferences(Set.of(NamedPhase.FULL_MOON), RIGHT), sample));
            assertFalse(matches(preferences(
                    Set.of(NamedPhase.FULL_MOON, NamedPhase.WAXING_GIBBOUS), RIGHT), sample));
            assertFalse(matches(preferences(null, RIGHT), sample));
        }
    }

    @Test
    void nonFullSamplesStillUseNormalBrightLimbMatching() {
        assertTrue(matches(preferences(
                Set.of(NamedPhase.FULL_MOON, NamedPhase.WAXING_GIBBOUS), RIGHT), WAXING_GIBBOUS));
        assertTrue(matches(preferences(null, RIGHT), WAXING_GIBBOUS));
        assertFalse(matches(preferences(null, new DegreeRange(100.0, 120.0)), WAXING_GIBBOUS));
    }

    @Test
    void fullMoonMatchesItsNamedPhaseWithoutABrightLimbFilter() {
        for (MoonSample sample : FULL_BUCKET) {
            assertTrue(matches(preferences(Set.of(NamedPhase.FULL_MOON), null), sample));
        }
    }

    private static boolean matches(OpportunityPreferences preferences, MoonSample sample) {
        return OpportunityHardFilter.matchesAll(PRAGUE, sample, ignored -> 0.25, preferences);
    }

    private static OpportunityPreferences preferences(
            Set<NamedPhase> namedPhases,
            DegreeRange brightLimb
    ) {
        return new OpportunityPreferences(
                1, null, null, null, namedPhases,
                brightLimb == null ? null : List.of(brightLimb));
    }

    private static MoonSample sample(double phase) {
        return new MoonSample(INSTANT, 0.0, 0.0, 90.0, phase, null, 0.0, 90.0);
    }
}
