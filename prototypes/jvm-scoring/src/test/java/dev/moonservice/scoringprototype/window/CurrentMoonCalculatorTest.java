package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static dev.moonservice.scoringprototype.window.CurrentMoonCalculator.BoundaryStatus.FOUND;
import static dev.moonservice.scoringprototype.window.CurrentMoonCalculator.BoundaryStatus.NOT_FOUND_WITHIN_RANGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentMoonCalculatorTest {
    private static final Instant AS_OF = Instant.parse("2026-08-06T12:00:00Z");

    @Test
    void findsTheNextRiseWhenMoonIsBelowTheHorizon() {
        Instant expectedRise = AS_OF.plus(Duration.ofMinutes(95));
        RecordingSamples samples = new RecordingSamples(instant -> sample(
                instant, hoursBetween(expectedRise, instant), -5.0));

        CurrentMoonCalculator.Result result = new CurrentMoonCalculator().calculate(AS_OF, samples);

        assertEquals(AS_OF, result.current().instant());
        assertTrue(result.current().moonAltitudeDegrees() < 0.0);
        assertNull(result.activePass());
        assertEquals(FOUND, result.nextRiseBoundary().status());
        assertWithinOneSecond(expectedRise, result.nextRiseBoundary().at());
        CurrentMoonCalculator.NextPass pass = nextPass(result);
        assertEquals(result.nextRiseBoundary(), pass.startBoundary());
        assertEquals(new CurrentMoonCalculator.Boundary(NOT_FOUND_WITHIN_RANGE, null), pass.endBoundary());
        assertEquals(expectedRise, pass.representedStartsAt());
        assertEquals(AS_OF.plus(Duration.ofHours(26)), pass.representedEndsAt());
        assertEquals(expectedRise, pass.pathSamples().getFirst().instant());
        assertEquals(AS_OF.plus(Duration.ofHours(26)), pass.pathSamples().getLast().instant());
        assertEquals(1, samples.callsAt(AS_OF));
        assertFalse(samples.requestedInstants().stream().anyMatch(
                instant -> instant.isAfter(AS_OF.plus(Duration.ofHours(26)))));
    }

    @Test
    void reportsNoNextRiseWithinTheInclusiveSearchRange() {
        RecordingSamples samples = new RecordingSamples(instant -> sample(instant, -0.01, -5.0));

        CurrentMoonCalculator.Result result = new CurrentMoonCalculator().calculate(AS_OF, samples);

        assertEquals(new CurrentMoonCalculator.Boundary(NOT_FOUND_WITHIN_RANGE, null),
                result.nextRiseBoundary());
        assertNull(result.nextPass());
        assertTrue(samples.requestedInstants().contains(AS_OF.plus(Duration.ofHours(26))));
    }

    @Test
    void keepsTheNextPassNullWhenTheRiseIsExactlyAtTheSearchEnd() {
        Instant searchEndsAt = AS_OF.plus(Duration.ofHours(26));
        RecordingSamples samples = new RecordingSamples(instant -> sample(
                instant, hoursBetween(searchEndsAt, instant), -5.0));

        CurrentMoonCalculator.Result result = new CurrentMoonCalculator().calculate(AS_OF, samples);

        assertEquals(new CurrentMoonCalculator.Boundary(FOUND, searchEndsAt), result.nextRiseBoundary());
        assertNull(result.nextPass());
        assertFalse(samples.requestedInstants().stream().anyMatch(instant -> instant.isAfter(searchEndsAt)));
    }

    @Test
    void treatsARiseExactlyAtAsOfAsThePassStartAndFindsTheLaterSet() {
        RecordingSamples samples = new RecordingSamples(instant -> {
            double hours = hoursBetween(AS_OF, instant);
            return sample(instant, hours * (3.0 - hours), -5.0);
        });

        CurrentMoonCalculator.ActivePass pass = activePass(new CurrentMoonCalculator().calculate(AS_OF, samples));

        assertEquals(FOUND, pass.startBoundary().status());
        assertEquals(AS_OF, pass.startBoundary().at());
        assertEquals(FOUND, pass.endBoundary().status());
        assertEquals(AS_OF.plus(Duration.ofHours(3)), pass.endBoundary().at());
        assertEquals(AS_OF, pass.representedStartsAt());
        assertEquals(AS_OF.plus(Duration.ofHours(3)), pass.representedEndsAt());
    }

    @Test
    void treatsASetExactlyAtAsOfAsThePassEndAndFindsTheEarlierRise() {
        RecordingSamples samples = new RecordingSamples(instant -> {
            double hours = hoursBetween(AS_OF, instant);
            return sample(instant, -hours * (hours + 3.0), -5.0);
        });

        CurrentMoonCalculator.ActivePass pass = activePass(new CurrentMoonCalculator().calculate(AS_OF, samples));

        assertEquals(FOUND, pass.startBoundary().status());
        assertEquals(AS_OF.minus(Duration.ofHours(3)), pass.startBoundary().at());
        assertEquals(FOUND, pass.endBoundary().status());
        assertEquals(AS_OF, pass.endBoundary().at());
        assertEquals(AS_OF.minus(Duration.ofHours(3)), pass.representedStartsAt());
        assertEquals(AS_OF, pass.representedEndsAt());
    }

    @Test
    void includesDirectionalCrossingsExactlyAtBothSearchEdges() {
        Instant searchStartsAt = AS_OF.minus(Duration.ofHours(26));
        Instant searchEndsAt = AS_OF.plus(Duration.ofHours(26));
        RecordingSamples samples = new RecordingSamples(instant -> {
            double hours = Math.abs(hoursBetween(AS_OF, instant));
            return sample(instant, 26.0 - hours, -5.0);
        });

        CurrentMoonCalculator.ActivePass pass = activePass(new CurrentMoonCalculator().calculate(AS_OF, samples));

        assertEquals(new CurrentMoonCalculator.Boundary(FOUND, searchStartsAt), pass.startBoundary());
        assertEquals(new CurrentMoonCalculator.Boundary(FOUND, searchEndsAt), pass.endBoundary());
        assertEquals(searchStartsAt, pass.representedStartsAt());
        assertEquals(searchEndsAt, pass.representedEndsAt());
        assertFalse(samples.requestedInstants().stream().anyMatch(instant -> instant.isBefore(searchStartsAt)));
        assertFalse(samples.requestedInstants().stream().anyMatch(instant -> instant.isAfter(searchEndsAt)));
    }

    @Test
    void clipsConstantAboveHorizonPassToMissingBoundaryEdges() {
        Instant searchStartsAt = AS_OF.minus(Duration.ofHours(26));
        Instant searchEndsAt = AS_OF.plus(Duration.ofHours(26));
        RecordingSamples samples = new RecordingSamples(instant -> sample(instant, 4.0, -5.0));

        CurrentMoonCalculator.ActivePass pass = activePass(new CurrentMoonCalculator().calculate(AS_OF, samples));

        assertEquals(new CurrentMoonCalculator.Boundary(NOT_FOUND_WITHIN_RANGE, null), pass.startBoundary());
        assertEquals(new CurrentMoonCalculator.Boundary(NOT_FOUND_WITHIN_RANGE, null), pass.endBoundary());
        assertEquals(searchStartsAt, pass.representedStartsAt());
        assertEquals(searchEndsAt, pass.representedEndsAt());
        assertEquals(searchStartsAt, pass.pathSamples().getFirst().instant());
        assertEquals(searchEndsAt, pass.pathSamples().getLast().instant());
        assertEquals(1, samples.callsAt(AS_OF));
    }

    @Test
    void refinesAContainingPassAcrossUtcMidnight() {
        Instant asOf = Instant.parse("2026-08-06T00:15:00Z");
        Instant expectedRise = Instant.parse("2026-08-05T23:45:00Z");
        Instant expectedSet = Instant.parse("2026-08-06T01:45:00Z");
        RecordingSamples samples = new RecordingSamples(instant -> {
            double hours = hoursBetween(asOf, instant);
            return sample(instant, -(hours + 0.5) * (hours - 1.5), -5.0);
        });

        CurrentMoonCalculator.ActivePass pass = activePass(new CurrentMoonCalculator().calculate(asOf, samples));

        assertWithinOneSecond(expectedRise, pass.startBoundary().at());
        assertWithinOneSecond(expectedSet, pass.endBoundary().at());
        assertEquals(LocalDate.of(2026, 8, 5), pass.representedStartsAt().atZone(ZoneOffset.UTC).toLocalDate());
        assertEquals(LocalDate.of(2026, 8, 6), pass.representedEndsAt().atZone(ZoneOffset.UTC).toLocalDate());
        assertTrue(pass.pathSamples().stream().anyMatch(sample -> sample.instant().equals(asOf)));
    }

    @Test
    void pathKeepsCadenceQuartersLightCrossingsAndOneExactCurrentSample() {
        Instant startsAt = AS_OF.minus(Duration.ofHours(3));
        Instant endsAt = AS_OF.plus(Duration.ofHours(2));
        RecordingSamples samples = new RecordingSamples(instant -> detailedSample(instant, startsAt));

        CurrentMoonCalculator.Result result = new CurrentMoonCalculator().calculate(AS_OF, samples);
        CurrentMoonCalculator.ActivePass pass = activePass(result);
        List<Instant> instants = pass.pathSamples().stream().map(MoonSample::instant).toList();

        assertEquals(startsAt, pass.startBoundary().at());
        assertEquals(endsAt, pass.endBoundary().at());
        assertTrue(instants.contains(startsAt.plus(Duration.ofMinutes(30))));
        assertTrue(instants.contains(startsAt.plus(Duration.ofMinutes(75))));
        assertTrue(instants.contains(startsAt.plus(Duration.ofMinutes(150))));
        assertTrue(instants.contains(startsAt.plus(Duration.ofMinutes(225))));
        assertEquals(1, instants.stream().filter(AS_OF::equals).count());
        assertEquals(instants.stream().distinct().toList(), instants);
        assertEquals(instants.stream().sorted().toList(), instants);
        assertLightCrossing(pass.pathSamples(), -12.0);
        assertLightCrossing(pass.pathSamples(), -6.0);
        assertLightCrossing(pass.pathSamples(), -0.833);
        assertLightCrossing(pass.pathSamples(), 6.0);

        MoonSample now = pass.pathSamples().stream()
                .filter(sample -> sample.instant().equals(AS_OF))
                .findFirst()
                .orElseThrow();
        assertSame(result.current(), now);
        assertEquals(118.0, now.moonAzimuthDegrees());
        assertEquals(78.8, now.moonIlluminationPercent());
        assertEquals(198.0, now.moonPhaseAngleDegrees());
        assertEquals(28.0, now.northPoleTiltDegrees());
        assertEquals(-0.833, now.sunAltitudeDegrees());
        assertEquals(259.0, now.sunAzimuthDegrees());
        assertEquals(1, samples.callsAt(AS_OF));
    }

    @Test
    void boundaryRequiresStatusAndInstantToAgree() {
        assertThrows(IllegalArgumentException.class, () -> new CurrentMoonCalculator.Boundary(FOUND, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CurrentMoonCalculator.Boundary(NOT_FOUND_WITHIN_RANGE, AS_OF));
    }

    private static CurrentMoonCalculator.ActivePass activePass(CurrentMoonCalculator.Result result) {
        assertNotNull(result.activePass());
        return result.activePass();
    }

    private static CurrentMoonCalculator.NextPass nextPass(CurrentMoonCalculator.Result result) {
        assertNotNull(result.nextPass());
        return result.nextPass();
    }

    private static void assertWithinOneSecond(Instant expected, Instant actual) {
        assertNotNull(actual);
        assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofSeconds(1)) <= 0);
    }

    private static void assertLightCrossing(List<MoonSample> samples, double threshold) {
        assertTrue(samples.stream()
                .anyMatch(sample -> Math.abs(sample.sunAltitudeDegrees() - threshold) < 0.01));
    }

    private static MoonSample detailedSample(Instant instant, Instant startsAt) {
        double minutes = Duration.between(startsAt, instant).toMillis() / 60_000.0;
        double hours = minutes / 60.0;
        double moonAltitude = hours * (5.0 - hours);
        double sunAltitude = instant.equals(AS_OF) ? -0.833 : -18.0 + (17.167 / 3.0) * hours;
        return new MoonSample(
                instant,
                moonAltitude,
                100.0 + minutes / 10.0,
                77.0 + minutes / 100.0,
                180.0 + minutes / 10.0,
                10.0 + minutes / 10.0,
                sunAltitude,
                250.0 + minutes / 20.0);
    }

    private static MoonSample sample(Instant instant, double moonAltitude, double sunAltitude) {
        return new MoonSample(instant, moonAltitude, 120.0, 90.0, 180.0, null, sunAltitude, 90.0);
    }

    private static double hoursBetween(Instant start, Instant end) {
        return Duration.between(start, end).toMillis() / 3_600_000.0;
    }

    private static final class RecordingSamples implements WindowGenerator.SampleProvider {
        private final Function<Instant, MoonSample> samples;
        private final Map<Instant, Integer> calls = new HashMap<>();
        private final List<Instant> requestedInstants = new ArrayList<>();

        private RecordingSamples(Function<Instant, MoonSample> samples) {
            this.samples = samples;
        }

        @Override
        public MoonSample sampleAt(Instant instant) {
            calls.merge(instant, 1, Integer::sum);
            requestedInstants.add(instant);
            return samples.apply(instant);
        }

        private int callsAt(Instant instant) {
            return calls.getOrDefault(instant, 0);
        }

        private List<Instant> requestedInstants() {
            return List.copyOf(requestedInstants);
        }
    }
}
