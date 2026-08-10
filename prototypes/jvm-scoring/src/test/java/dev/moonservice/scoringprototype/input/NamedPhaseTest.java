package dev.moonservice.scoringprototype.input;

import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamedPhaseTest {
    @ParameterizedTest
    @CsvSource({
            "0.0, NEW_MOON, new_moon",
            "22.5, WAXING_CRESCENT, waxing_crescent",
            "67.5, FIRST_QUARTER, first_quarter",
            "112.5, WAXING_GIBBOUS, waxing_gibbous",
            "157.5, FULL_MOON, full_moon",
            "202.5, WANING_GIBBOUS, waning_gibbous",
            "247.5, LAST_QUARTER, last_quarter",
            "292.5, WANING_CRESCENT, waning_crescent",
            "337.5, NEW_MOON, new_moon"
    })
    void classifiesEachInclusiveSectorStart(
            double angle,
            NamedPhase expected,
            String expectedWireValue
    ) {
        NamedPhase phase = NamedPhase.fromPhaseAngleDegrees(angle);

        assertEquals(expected, phase);
        assertEquals(expectedWireValue, phase.wireValue());
    }

    @ParameterizedTest
    @CsvSource({
            "22.5, NEW_MOON",
            "67.5, WAXING_CRESCENT",
            "112.5, FIRST_QUARTER",
            "157.5, WAXING_GIBBOUS",
            "202.5, FULL_MOON",
            "247.5, WANING_GIBBOUS",
            "292.5, LAST_QUARTER",
            "337.5, WANING_CRESCENT"
    })
    void keepsThePreviousPhaseUntilEachTransition(double boundary, NamedPhase expected) {
        assertEquals(expected, NamedPhase.fromPhaseAngleDegrees(boundary - 0.000_001));
    }

    @ParameterizedTest
    @CsvSource({
            "-337.5, WAXING_CRESCENT, waxing_crescent",
            "-67.5, WANING_CRESCENT, waning_crescent",
            "-1.0, NEW_MOON, new_moon",
            "360.0, NEW_MOON, new_moon",
            "382.5, WAXING_CRESCENT, waxing_crescent",
            "877.5, FULL_MOON, full_moon"
    })
    void normalizesRepresentativeWrappedAngles(
            double angle,
            NamedPhase expected,
            String expectedWireValue
    ) {
        NamedPhase phase = NamedPhase.fromPhaseAngleDegrees(angle);

        assertEquals(expected, phase);
        assertEquals(expectedWireValue, phase.wireValue());
    }
}
