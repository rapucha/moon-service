package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.*;
import dev.moonservice.backend.location.*;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.*;
import dev.moonservice.scoringprototype.window.RefinedTimeGrid;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LunarEclipseEventServiceTest {
    private static final ResolvedLocation PRAGUE = new TestOpenMeteoLocationResolver()
            .resolveLocationId("prague-cz").singleCandidate().orElseThrow();

    private final LunarEclipseEventService service = new LunarEclipseEventService();

    @Test
    void returnsVisibleTotalPartialAndPenumbralEclipsesWithObjectiveFacts() {
        List<LunarEclipseEvent> events = service.discover(
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2027-03-01T01:00:00Z"),
                prototype(PRAGUE),
                OpportunityPreferences.none());

        assertThat(events).extracting(LunarEclipseEvent::subtype)
                .containsExactly("total", "partial", "penumbral");
        assertThat(events).extracting(LunarEclipseEvent::maximumAt)
                .containsExactly(
                        "2025-09-07T18:11:41.502Z",
                        "2026-08-28T04:12:49.077Z",
                        "2027-02-20T23:12:44.142Z");
        assertThat(events).noneMatch(event ->
                event.maximumAt().equals("2026-03-03T11:33:40.289Z"));

        LunarEclipseEvent total = events.getFirst();
        assertThat(total.kind()).isEqualTo("lunar_eclipse");
        assertThat(total.startsAt()).isEqualTo("2025-09-07T15:28:02.516Z");
        assertThat(total.endsAt()).isEqualTo("2025-09-07T20:55:20.487Z");
        assertThat(total.phases()).extracting(EclipsePhase::kind)
                .containsExactly("penumbral", "partial", "total");
        assertThat(total.phases().get(1).startsAt())
                .isEqualTo("2025-09-07T16:26:40.111Z");
        assertThat(total.phases().get(2).endsAt())
                .isEqualTo("2025-09-07T18:53:08.096Z");
        assertThat(total.localVisibility().displayInterval().suggestedAt())
                .isEqualTo(total.maximumAt());
        assertThat(total.shadowSamples()).extracting(EclipseShadowSample::at)
                .containsExactly(
                        "2025-09-07T15:28:02.516Z",
                        "2025-09-07T16:26:40.111Z",
                        "2025-09-07T17:30:14.908Z",
                        "2025-09-07T18:11:41.502Z",
                        "2025-09-07T18:53:08.096Z",
                        "2025-09-07T19:56:42.892Z",
                        "2025-09-07T20:55:20.487Z");
        EclipseShadowSample maximumShadow = total.shadowSamples().get(3);
        assertThat(maximumShadow.moon().northPoleTiltDegrees()).isNotNull();
        assertThat(maximumShadow.shadow().centerRightMoonRadii())
                .isCloseTo(-0.1673, within(0.0001));
        assertThat(maximumShadow.shadow().centerUpMoonRadii())
                .isCloseTo(0.9953, within(0.0001));
        assertThat(maximumShadow.shadow().penumbraRadiusMoonRadii())
                .isGreaterThan(maximumShadow.shadow().umbraRadiusMoonRadii());
        assertThat(total.moonAtMaximum().altitudeDegrees())
                .isCloseTo(5.730577, within(0.00001));
        assertThat(total.moonAtMaximum().azimuthDegrees())
                .isCloseTo(107.552074, within(0.00001));
        assertThat(total.preferenceAssessment().overall())
                .isEqualTo("no_active_preferences");
        assertThat(total.preferenceAssessment().filters()).isEmpty();
        assertThat(total.weather()).isEqualTo(Weather.outsideForecastHorizon());

        LunarEclipseEvent partial = events.get(1);
        assertThat(partial.umbralObscurationPercent())
                .isCloseTo(96.6055593, within(0.000001));
        assertThat(partial.phases()).extracting(EclipsePhase::kind)
                .containsExactly("penumbral", "partial");
        assertThat(partial.shadowSamples()).hasSize(5);
        assertThat(partial.localVisibility().status()).isEqualTo("partly_visible");

        LunarEclipseEvent penumbral = events.get(2);
        assertThat(penumbral.umbralObscurationPercent()).isZero();
        assertThat(penumbral.phases()).extracting(EclipsePhase::kind)
                .containsExactly("penumbral");
        assertThat(penumbral.shadowSamples()).hasSize(3);
        assertThat(penumbral.localVisibility().status()).isEqualTo("fully_visible");
    }

    @Test
    void returnsEmptyWhenNoEclipseIsVisibleFromTheLocation() {
        assertThat(service.discover(
                Instant.parse("2014-01-01T00:00:00Z"),
                Instant.parse("2015-06-30T23:00:00Z"),
                prototype(PRAGUE),
                OpportunityPreferences.none()))
                .isEmpty();
    }

    @Test
    void includesAnAlreadyRunningEclipseAndClampsOnlyItsDisplayInterval() {
        LunarEclipseEvent event = eventAt(service.discover(
                        Instant.parse("2026-08-28T04:13:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z"),
                        prototype(PRAGUE),
                        OpportunityPreferences.none()),
                "2026-08-28T04:12:49.077Z");

        assertThat(event.startsAt()).isEqualTo("2026-08-28T01:23:36.003Z");
        assertThat(event.endsAt()).isEqualTo("2026-08-28T07:02:02.151Z");
        assertThat(event.localVisibility().selectedInterval().startsAt())
                .isEqualTo("2026-08-28T01:23:36.003Z");
        assertThat(event.localVisibility().selectedInterval().endsAt())
                .isBetween("2026-08-28T04:15:25Z", "2026-08-28T04:15:27Z");
        assertThat(event.localVisibility().displayInterval().startsAt())
                .isEqualTo("2026-08-28T04:13:00Z");
        assertThat(event.localVisibility().displayInterval().suggestedAt())
                .isEqualTo("2026-08-28T04:13:00Z");
        assertThat(event.localVisibility().displayInterval().endsAt())
                .isEqualTo(event.localVisibility().selectedInterval().endsAt());
        assertThat(event.shadowSamples()).hasSize(6);
        assertThat(event.shadowSamples()).extracting(EclipseShadowSample::at)
                .contains("2026-08-28T04:13:00Z");
    }

    @Test
    void keepsTheHorizonEndExclusiveForSuggestions() {
        Instant maximum = Instant.parse("2027-02-20T23:12:44.142Z");
        Instant startsAt = maximum.atZone(PRAGUE.zoneId()).minusMonths(18).toInstant();
        LunarEclipseEvent eclipse = eventAt(service.discover(
                startsAt,
                maximum,
                prototype(PRAGUE),
                OpportunityPreferences.none()), maximum.toString());

        assertThat(Instant.parse(eclipse.localVisibility().displayInterval().suggestedAt()))
                .isEqualTo(maximum.minusSeconds(1));
    }

    @Test
    void representsNotVisiblePhasesAndSelectsEarlierEquidistantInterval() {
        LunarEclipseEvent settingEclipse = eventAt(service.discover(
                        Instant.parse("2021-11-19T06:00:00Z"),
                        Instant.parse("2021-12-01T00:00:00Z"),
                        prototype(PRAGUE),
                        OpportunityPreferences.none()),
                "2021-11-19T09:02:55.259Z");
        EclipsePhase partial = settingEclipse.phases().stream()
                .filter(phase -> phase.kind().equals("partial"))
                .findFirst().orElseThrow();
        assertThat(partial.localVisibility().status()).isEqualTo("not_visible");
        assertThat(partial.localVisibility().intervals()).isEmpty();

        ResolvedLocation polar = location(
                "polar-test", 80.5, -165.57924, ZoneOffset.UTC);
        LunarEclipseEvent split = eventAt(service.discover(
                        Instant.parse("2027-02-20T20:00:00Z"),
                        Instant.parse("2027-02-21T08:00:00Z"),
                        prototype(polar),
                        OpportunityPreferences.none()),
                "2027-02-20T23:12:44.142Z");
        assertThat(split.localVisibility().intervals()).hasSize(2);
        assertThat(split.localVisibility().selectedInterval())
                .isEqualTo(split.localVisibility().intervals().getFirst());

        RefinedTimeGrid.Interval earlier = new RefinedTimeGrid.Interval(
                Instant.parse("2027-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T01:00:00Z"));
        RefinedTimeGrid.Interval later = new RefinedTimeGrid.Interval(
                Instant.parse("2027-01-01T03:00:00Z"),
                Instant.parse("2027-01-01T04:00:00Z"));
        assertThat(EventLocalViewing.select(
                List.of(later, earlier), Instant.parse("2027-01-01T02:00:00Z")))
                .isEqualTo(earlier);
    }

    @Test
    void assessesOnlyAltitudeAndAzimuthWithoutChangingTheEvent() {
        LunarEclipseEvent allOff = firstEvent(OpportunityPreferences.none());
        assertThat(allOff.preferenceAssessment().overall())
                .isEqualTo("no_active_preferences");
        assertThat(allOff.preferenceAssessment().filters()).isEmpty();

        OpportunityPreferences everyFilter = new OpportunityPreferences(
                1,
                new AltitudeRange(5, 6),
                new AzimuthPreference(new DegreeRange(107, 108), null),
                new TimePreference(
                        TimeMode.LOCAL_CLOCK,
                        new LocalClockWindow(LocalTime.of(0, 0), LocalTime.of(1, 0)),
                        null),
                Set.of(NamedPhase.NEW_MOON),
                List.of(new DegreeRange(0, 1)));
        LunarEclipseEvent matching = firstEvent(everyFilter);
        assertThat(matching.preferenceAssessment().overall()).isEqualTo("matches");
        assertThat(matching.preferenceAssessment().filters()).containsExactly(
                new FilterAssessment("altitudeDegrees", "matches"),
                new FilterAssessment("azimuthDegrees", "matches"));

        OpportunityPreferences irrelevantOnly = new OpportunityPreferences(
                1,
                null,
                null,
                new TimePreference(
                        TimeMode.LIGHT_BUCKET, null, Set.of(AmbientLight.DAYLIGHT)),
                Set.of(NamedPhase.NEW_MOON),
                List.of(new DegreeRange(0, 1)));
        LunarEclipseEvent irrelevant = firstEvent(irrelevantOnly);
        assertThat(irrelevant.preferenceAssessment().overall())
                .isEqualTo("no_active_preferences");
        assertThat(irrelevant.preferenceAssessment().filters()).isEmpty();

        OpportunityPreferences wrongAltitude = new OpportunityPreferences(
                1,
                new AltitudeRange(20, 30),
                new AzimuthPreference(new DegreeRange(107, 108), null),
                null,
                null,
                null);
        LunarEclipseEvent mismatch = firstEvent(wrongAltitude);
        assertThat(mismatch.preferenceAssessment().overall())
                .isEqualTo("does_not_match");
        assertThat(mismatch.preferenceAssessment().filters()).containsExactly(
                new FilterAssessment("altitudeDegrees", "does_not_match"),
                new FilterAssessment("azimuthDegrees", "matches"));

        assertThat(List.of(matching, irrelevant, mismatch)).allSatisfy(event -> {
            assertThat(event.id()).isEqualTo(allOff.id());
            assertThat(event.maximumAt()).isEqualTo(allOff.maximumAt());
            assertThat(event.localVisibility()).isEqualTo(allOff.localVisibility());
            assertThat(event.shadowSamples()).isEqualTo(allOff.shadowSamples());
        });
    }

    private LunarEclipseEvent firstEvent(OpportunityPreferences preferences) {
        return service.discover(
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-09-08T22:00:00Z"),
                prototype(PRAGUE),
                preferences).getFirst();
    }

    private static LunarEclipseEvent eventAt(
            List<LunarEclipseEvent> events,
            String maximumAt
    ) {
        return events.stream()
                .filter(event -> event.maximumAt().equals(maximumAt))
                .findFirst()
                .orElseThrow();
    }

    private static dev.moonservice.scoringprototype.fixture.Location prototype(
            ResolvedLocation location
    ) {
        return new dev.moonservice.scoringprototype.fixture.Location(
                location.locationId(),
                "real_location",
                location.locationId(),
                location.displayName(),
                location.latitude(),
                location.longitude(),
                location.elevationMeters(),
                location.zoneId().getId(),
                location.countryCode());
    }

    private static ResolvedLocation location(
            String id,
            double latitude,
            double longitude,
            ZoneId zoneId
    ) {
        return new ResolvedLocation(
                id,
                new ProviderLocationId(LocationProvider.OPEN_METEO, id),
                id,
                latitude,
                longitude,
                0,
                zoneId,
                "XX");
    }
}
