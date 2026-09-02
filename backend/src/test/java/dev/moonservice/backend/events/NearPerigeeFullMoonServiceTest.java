package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.FilterAssessment;
import dev.moonservice.backend.events.MoonEventResponse.FullMoonEvent;
import dev.moonservice.backend.events.MoonEventResponse.FullMoonQualifier;
import dev.moonservice.backend.events.MoonEventResponse.DisplayInterval;
import dev.moonservice.backend.events.MoonEventResponse.MoonPathSample;
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AzimuthPreference;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimePreference;
import dev.moonservice.scoringprototype.window.RefinedTimeGrid;
import dev.moonservice.scoringprototype.window.WindowGenerator;
import io.github.cosinekitty.astronomy.ApsisInfo;
import io.github.cosinekitty.astronomy.ApsisKind;
import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.Time;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NearPerigeeFullMoonServiceTest {
    private static final Location PRAGUE = new Location(
            "prague-cz",
            "real_location",
            "prague-cz",
            "Prague, Czechia",
            50.0755,
            14.4378,
            200,
            "Europe/Prague",
            "CZ");

    private final NearPerigeeFullMoonService service =
            new NearPerigeeFullMoonService();

    @Test
    void qualifiesExactFullMoonFromUnroundedPublicAstronomyValues() {
        FullMoonEvent event = eventAt(service.discover(
                        Instant.parse("2027-01-01T00:00:00Z"),
                        Instant.parse("2027-02-01T00:00:00Z"),
                        PRAGUE,
                        OpportunityPreferences.none()),
                "2027-01-22T12:17:50.281Z");

        assertThat(event.id())
                .isEqualTo("full-moon-07e5fe49-ab54-3187-a249-76ca1dbd9130");
        assertThat(event.kind()).isEqualTo("full_moon");
        assertMoonPath(event, PRAGUE);
        assertThat(event.qualifiers()).hasSize(1);
        FullMoonQualifier qualifier = event.qualifiers().getFirst();
        assertThat(qualifier.kind()).isEqualTo("near_perigee");
        assertThat(qualifier.definitionVersion()).isEqualTo(1);
        assertThat(qualifier.distanceKilometersAtPeak())
                .isCloseTo(357634.79259981716, within(0.000001));
        assertThat(qualifier.perigeeDistanceKilometers())
                .isCloseTo(357272.55244686815, within(0.000001));
        assertThat(qualifier.apogeeDistanceKilometers())
                .isCloseTo(406178.2267796418, within(0.000001));
        assertThat(qualifier.closeness())
                .isCloseTo(0.992593085406, within(0.000000000001));

        Time fullMoon = Astronomy.searchMoonPhase(
                180.0, astronomyTime(Instant.parse("2027-01-01T00:00:00Z")), 40.0);
        assertThat(fullMoon).isNotNull();
        ApsisInfo before = Astronomy.searchLunarApsis(fullMoon.addDays(-60.0));
        ApsisInfo after = Astronomy.nextLunarApsis(before);
        while (instant(after.getTime()).isBefore(instant(fullMoon))) {
            before = after;
            after = Astronomy.nextLunarApsis(after);
        }
        double perigee = (before.getKind() == ApsisKind.Pericenter ? before : after)
                .getDistanceKm();
        double apogee = (before.getKind() == ApsisKind.Apocenter ? before : after)
                .getDistanceKm();
        double fullDistance = Astronomy.geoMoon(fullMoon).length() * Astronomy.KM_PER_AU;
        assertThat(qualifier.closeness())
                .isCloseTo(
                        (apogee - fullDistance) / (apogee - perigee),
                        within(0.000000000001));
    }

    @Test
    void includesTheExactPeakWhenItIsVisibleOnTheSelectedMoonPath() {
        FullMoonEvent event = eventAt(service.discover(
                        Instant.parse("2026-11-01T00:00:00Z"),
                        Instant.parse("2026-12-01T00:00:00Z"),
                        PRAGUE,
                        OpportunityPreferences.none()),
                "2026-11-24T14:54:04.191Z");

        assertThat(event.localViewing().displayInterval().suggestedAt())
                .isEqualTo(event.peakAt());
        assertThat(event.localViewing().moonPath().samples())
                .extracting(MoonPathSample::at)
                .contains(event.peakAt());
    }

    @Test
    void usesAnInclusiveUnroundedThresholdAndExcludesTheNextLowerCase() {
        assertThat(NearPerigeeFullMoonService.isNearPerigee(0.90)).isTrue();
        assertThat(NearPerigeeFullMoonService.isNearPerigee(Math.nextDown(0.90)))
                .isFalse();

        assertThat(service.discover(
                Instant.parse("2027-12-01T00:00:00Z"),
                Instant.parse("2027-12-20T00:00:00Z"),
                PRAGUE,
                OpportunityPreferences.none())).isEmpty();

        Time fullMoon = Astronomy.searchMoonPhase(
                180.0, astronomyTime(Instant.parse("2027-12-01T00:00:00Z")), 40.0);
        assertThat(instant(fullMoon)).isEqualTo(
                Instant.parse("2027-12-13T16:09:18.504Z"));
        assertThat(closeness(fullMoon))
                .isCloseTo(0.898775885605, within(0.00000000001));
    }

    @Test
    void doesNotTurnAPerigeeWithoutAnExactFullMoonIntoAnEvent() {
        ApsisInfo apsis = Astronomy.searchLunarApsis(
                astronomyTime(Instant.parse("2027-05-01T00:00:00Z")));
        ApsisInfo perigee = apsis.getKind() == ApsisKind.Pericenter
                ? apsis
                : Astronomy.nextLunarApsis(apsis);
        assertThat(perigee.getKind()).isEqualTo(ApsisKind.Pericenter);
        Instant perigeeAt = instant(perigee.getTime());

        assertThat(service.discover(
                perigeeAt.minusSeconds(3_600),
                perigeeAt.plusSeconds(3_600),
                PRAGUE,
                OpportunityPreferences.none())).isEmpty();
    }

    @Test
    void searchesTheCompleteUsefulDomainAndIncludesViewingAfterAnEarlierPeak() {
        FullMoonEvent event = eventAt(service.discover(
                        Instant.parse("2027-01-22T15:00:00Z"),
                        Instant.parse("2027-01-23T12:00:00Z"),
                        PRAGUE,
                        OpportunityPreferences.none()),
                "2027-01-22T12:17:50.281Z");

        assertThat(Instant.parse(event.peakAt()))
                .isBefore(Instant.parse("2027-01-22T15:00:00Z"));
        assertThat(event.localViewing()).isNotNull();
        assertThat(event.localViewing().intervals()).hasSize(2);
        assertThat(event.localViewing().selectedInterval())
                .isEqualTo(event.localViewing().intervals().get(1));
        assertThat(Instant.parse(event.localViewing().displayInterval().startsAt()))
                .isAfterOrEqualTo(Instant.parse("2027-01-22T15:00:00Z"));
        assertThat(event.localViewing().displayInterval().suggestedAt())
                .isEqualTo(event.localViewing().displayInterval().startsAt());
    }

    @Test
    void retainsPeakInsideTheHorizonWithoutLocalFacts() {
        Location antarctic = location("antarctic", -70.0, 0.0, "UTC");
        Instant horizonStartsAt = Instant.parse("2025-07-22T20:00:00Z");
        Instant horizonEndsAt = Instant.parse("2027-01-22T20:00:00Z");
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                new AltitudeRange(10, 20),
                new AzimuthPreference(new DegreeRange(90, 120), null),
                new TimePreference(
                        TimeMode.LIGHT_BUCKET, null, Set.of(AmbientLight.NIGHT)),
                Set.of(NamedPhase.FULL_MOON),
                List.of(new DegreeRange(0, 10)));

        FullMoonEvent event = eventAt(service.discover(
                        horizonStartsAt,
                        horizonEndsAt,
                        antarctic,
                        preferences),
                "2027-01-22T12:17:50.281Z");

        assertThat(event.localViewing()).isNull();
        assertThat(event.weather()).isNull();
        assertThat(event.preferenceAssessment().overall()).isEqualTo("not_applicable");
        assertThat(event.preferenceAssessment().filters()).containsExactly(
                new FilterAssessment("altitudeDegrees", "not_applicable"),
                new FilterAssessment("azimuthDegrees", "not_applicable"));

        FullMoonEvent withLaterHorizon = eventAt(service.discover(
                        horizonStartsAt,
                        Instant.parse("2027-01-23T12:17:51Z"),
                        antarctic,
                        OpportunityPreferences.none()),
                event.peakAt());
        assertThat(withLaterHorizon.localViewing()).isNotNull();
        assertMoonPath(withLaterHorizon, antarctic);
        assertThat(withLaterHorizon.localViewing().intervals()).isNotEmpty();
        assertThat(withLaterHorizon.localViewing().intervals()).allSatisfy(interval ->
                assertThat(Instant.parse(interval.startsAt()))
                        .isAfterOrEqualTo(horizonEndsAt));
    }

    @Test
    void doesNotIncludeAnExclusiveEndPeakWithoutOverlappingViewing() {
        Instant peak = Instant.parse("2027-01-22T12:17:50.281Z");
        Location antarctic = location("deep-antarctic", -80.5, 0.0, "UTC");

        assertThat(service.discover(
                        peak.minusSeconds(2 * 24 * 60 * 60),
                        peak,
                        antarctic,
                        OpportunityPreferences.none()))
                .noneMatch(event -> event.peakAt().equals(peak.toString()));
    }

    @Test
    void preferencesDoNotMoveOrDuplicateTheAstronomicalEvent() {
        FullMoonEvent allOff = eventAt(service.discover(
                        Instant.parse("2027-01-01T00:00:00Z"),
                        Instant.parse("2027-02-01T00:00:00Z"),
                        PRAGUE,
                        OpportunityPreferences.none()),
                "2027-01-22T12:17:50.281Z");
        OpportunityPreferences mismatch = new OpportunityPreferences(
                1,
                new AltitudeRange(80, 90),
                null,
                new TimePreference(
                        TimeMode.LIGHT_BUCKET, null, Set.of(AmbientLight.DAYLIGHT)),
                Set.of(NamedPhase.NEW_MOON),
                List.of(new DegreeRange(0, 1)));
        FullMoonEvent filtered = eventAt(service.discover(
                Instant.parse("2027-01-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"),
                PRAGUE,
                mismatch),
                allOff.peakAt());
        FullMoonEvent generatedEarlier = eventAt(service.discover(
                        Instant.parse("2026-12-01T00:00:00Z"),
                        Instant.parse("2027-02-01T00:00:00Z"),
                        PRAGUE,
                        OpportunityPreferences.none()),
                allOff.peakAt());

        assertThat(filtered.id()).isEqualTo(allOff.id());
        assertThat(filtered.qualifiers()).isEqualTo(allOff.qualifiers());
        assertThat(filtered.localViewing()).isEqualTo(allOff.localViewing());
        assertThat(generatedEarlier.id()).isEqualTo(allOff.id());
        assertThat(generatedEarlier.peakAt()).isEqualTo(allOff.peakAt());
        assertThat(filtered.preferenceAssessment().filters()).containsExactly(
                new FilterAssessment("altitudeDegrees", "does_not_match"));
    }

    @Test
    void completesOrdinaryMoonsetBeyondEventSearchBoundary() {
        Location equatorDateline = location("equator-dateline", 0.0, -180.0, "UTC");
        FullMoonEvent event = service.discover(
                Instant.parse("2027-01-23T07:00:00Z"),
                Instant.parse("2027-01-24T00:00:00Z"),
                equatorDateline,
                OpportunityPreferences.none()).getFirst();
        Instant peakAt = Instant.parse(event.peakAt());
        Instant pathEndsAt = Instant.parse(
                event.localViewing().moonPath().samples().getLast().at());

        assertThat(pathEndsAt).isAfter(peakAt.plus(Duration.ofHours(24)));
        assertMoonPath(event, equatorDateline);
    }

    private static FullMoonEvent eventAt(List<FullMoonEvent> events, String peakAt) {
        return events.stream()
                .filter(event -> event.peakAt().equals(peakAt))
                .findFirst()
                .orElseThrow();
    }

    private static void assertMoonPath(FullMoonEvent event, Location location) {
        DisplayInterval display = event.localViewing().displayInterval();
        List<MoonPathSample> samples = event.localViewing().moonPath().samples();
        Instant pathStartsAt = Instant.parse(samples.getFirst().at());
        Instant pathEndsAt = Instant.parse(samples.getLast().at());
        Instant peakAt = Instant.parse(event.peakAt());
        Instant pathDomainStartsAt = peakAt.minus(Duration.ofHours(50));
        Instant pathDomainEndsAt = peakAt.plus(Duration.ofHours(50));
        Instant suggestedAt = Instant.parse(display.suggestedAt());
        List<Instant> requiredInstants = Stream.of(
                        peakAt.minus(Duration.ofHours(24)),
                        peakAt.plus(Duration.ofHours(24)),
                        peakAt,
                        suggestedAt)
                .filter(instant -> !instant.isBefore(pathDomainStartsAt)
                        && !instant.isAfter(pathDomainEndsAt))
                .distinct()
                .toList();
        EphemerisSampler ephemeris = new EphemerisSampler();
        RefinedTimeGrid.Interval aboveHorizon = RefinedTimeGrid.matchingIntervals(
                        pathDomainStartsAt,
                        pathDomainStartsAt,
                        pathDomainEndsAt,
                        requiredInstants,
                        instant -> ephemeris.sampleAt(location, instant).moonAltitudeDegrees() >= 0.0)
                .stream()
                .filter(interval -> !suggestedAt.isBefore(interval.startsAt())
                        && !suggestedAt.isAfter(interval.endsAt()))
                .findFirst()
                .orElseThrow();
        Instant selectedStartsAt = Instant.parse(event.localViewing().selectedInterval().startsAt());
        Instant selectedEndsAt = Instant.parse(event.localViewing().selectedInterval().endsAt());
        Instant expectedStartsAt = aboveHorizon.startsAt().isBefore(selectedStartsAt)
                ? aboveHorizon.startsAt() : selectedStartsAt;
        Instant expectedEndsAt = aboveHorizon.endsAt().isAfter(selectedEndsAt)
                ? aboveHorizon.endsAt() : selectedEndsAt;
        List<Instant> specialInstants = requiredInstants.stream()
                .filter(instant -> !instant.isBefore(expectedStartsAt)
                        && !instant.isAfter(expectedEndsAt))
                .toList();
        List<String> expectedInstants = WindowGenerator.pathSamples(
                        instant -> ephemeris.sampleAt(location, instant),
                        expectedStartsAt,
                        specialInstants,
                        expectedEndsAt).stream()
                .map(MoonSample::instant)
                .map(Instant::toString)
                .toList();

        assertThat(pathStartsAt).isEqualTo(expectedStartsAt);
        assertThat(pathEndsAt).isEqualTo(expectedEndsAt);
        assertThat(samples).extracting(MoonPathSample::at)
                .containsExactlyElementsOf(expectedInstants)
                .contains(display.suggestedAt());
        assertThat(pathStartsAt).isBeforeOrEqualTo(Instant.parse(display.startsAt()));
        assertThat(pathEndsAt).isAfterOrEqualTo(Instant.parse(display.endsAt()));
        assertThat(samples).allSatisfy(sample -> {
            assertThat(sample.altitudeDegrees()).isFinite();
            assertThat(sample.azimuthDegrees()).isFinite();
            assertThat(sample.moonPhaseAngleDegrees()).isFinite();
            assertThat(sample.sunAltitudeDegrees()).isFinite();
            assertThat(sample.sunAzimuthDegrees()).isFinite();
            assertThat(sample.lightBucket()).isNotBlank();
            assertThat(sample.shadow()).isNull();
        });
    }

    private static double closeness(Time fullMoon) {
        ApsisInfo before = Astronomy.searchLunarApsis(fullMoon.addDays(-60.0));
        ApsisInfo after = Astronomy.nextLunarApsis(before);
        while (instant(after.getTime()).isBefore(instant(fullMoon))) {
            before = after;
            after = Astronomy.nextLunarApsis(after);
        }
        double perigee = (before.getKind() == ApsisKind.Pericenter ? before : after)
                .getDistanceKm();
        double apogee = (before.getKind() == ApsisKind.Apocenter ? before : after)
                .getDistanceKm();
        double fullDistance = Astronomy.geoMoon(fullMoon).length() * Astronomy.KM_PER_AU;
        return (apogee - fullDistance) / (apogee - perigee);
    }

    private static Location location(
            String id,
            double latitude,
            double longitude,
            String timezone
    ) {
        return new Location(
                id,
                "real_location",
                id,
                id,
                latitude,
                longitude,
                0,
                timezone,
                "XX");
    }

    private static Time astronomyTime(Instant instant) {
        return Time.fromMillisecondsSince1970(instant.toEpochMilli());
    }

    private static Instant instant(Time time) {
        return Instant.ofEpochMilli(time.toMillisecondsSince1970());
    }
}
