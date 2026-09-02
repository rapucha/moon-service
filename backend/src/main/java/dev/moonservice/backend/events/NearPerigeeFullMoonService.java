package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.FullMoonEvent;
import dev.moonservice.backend.events.MoonEventResponse.FullMoonQualifier;
import dev.moonservice.backend.events.MoonEventResponse.LocalViewing;
import dev.moonservice.backend.events.MoonEventResponse.PreferenceAssessment;
import dev.moonservice.backend.events.MoonEventResponse.Weather;
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import io.github.cosinekitty.astronomy.ApsisInfo;
import io.github.cosinekitty.astronomy.ApsisKind;
import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.Time;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
final class NearPerigeeFullMoonService {
    static final int DEFINITION_VERSION = 1;
    static final double MINIMUM_CLOSENESS = 0.90;

    private static final double FULL_MOON_PHASE_DEGREES = 180.0;
    private static final double PHASE_SEARCH_LIMIT_DAYS = 40.0;
    private static final Duration VIEWING_RADIUS = Duration.ofHours(24);
    // A pass may meet the viewing edge just after moonrise; another lunar day finds moonset.
    private static final Duration PATH_RADIUS = Duration.ofHours(50);
    private static final double APSIS_SEARCH_LOOKBACK_DAYS = 60.0;

    private final EphemerisSampler ephemeris = new EphemerisSampler();

    List<FullMoonEvent> discover(
            Instant startsAt,
            Instant endsAt,
            Location location,
            OpportunityPreferences preferences
    ) {
        List<FullMoonEvent> events = new ArrayList<>();
        Time fullMoon = nextFullMoon(astronomyTime(startsAt.minus(VIEWING_RADIUS)));
        Instant searchEndsAt = endsAt.plus(VIEWING_RADIUS);
        while (instant(fullMoon).isBefore(searchEndsAt)) {
            FullMoonEvent event = event(fullMoon, startsAt, endsAt, location, preferences);
            if (event != null) {
                events.add(event);
            }
            fullMoon = nextFullMoon(fullMoon.addDays(1.0));
        }
        return events.stream()
                .sorted(Comparator.comparing(FullMoonEvent::peakAt)
                        .thenComparing(FullMoonEvent::id))
                .toList();
    }

    private FullMoonEvent event(
            Time fullMoon,
            Instant horizonStartsAt,
            Instant horizonEndsAt,
            Location location,
            OpportunityPreferences preferences
    ) {
        Apsides apsides = apsidesAround(fullMoon);
        double distanceAtPeak = Astronomy.geoMoon(fullMoon).length() * Astronomy.KM_PER_AU;
        double closeness = closeness(
                distanceAtPeak,
                apsides.perigeeDistanceKilometers(),
                apsides.apogeeDistanceKilometers());
        if (!isNearPerigee(closeness)) {
            return null;
        }

        Instant peakAt = instant(fullMoon);
        EventLocalViewing.Result viewing = EventLocalViewing.calculate(
                ephemeris,
                location,
                peakAt.minus(VIEWING_RADIUS),
                peakAt.plus(VIEWING_RADIUS),
                peakAt.minus(PATH_RADIUS),
                peakAt.plus(PATH_RADIUS),
                List.of(),
                horizonStartsAt,
                horizonEndsAt,
                peakAt);
        boolean peakInsideHorizon = !peakAt.isBefore(horizonStartsAt)
                && peakAt.isBefore(horizonEndsAt);
        if (!peakInsideHorizon && viewing.localViewing() == null) {
            return null;
        }

        LocalViewing localViewing = viewing.localViewing();
        PreferenceAssessment assessment;
        Weather weather;
        if (localViewing == null) {
            assessment = EventPreferenceEvaluator.notApplicable(preferences);
            weather = null;
        } else {
            Instant suggestedAt = Instant.parse(localViewing.displayInterval().suggestedAt());
            assessment = EventPreferenceEvaluator.evaluate(
                    preferences,
                    viewing.suggestedSample(),
                    ephemeris.topocentricLunarAngularRadiusDegrees(location, suggestedAt));
            weather = Weather.outsideForecastHorizon();
        }
        FullMoonQualifier qualifier = new FullMoonQualifier(
                "near_perigee",
                DEFINITION_VERSION,
                closeness,
                distanceAtPeak,
                apsides.perigeeDistanceKilometers(),
                apsides.apogeeDistanceKilometers());
        return new FullMoonEvent(
                stableId(peakAt),
                "full_moon",
                peakAt.toString(),
                List.of(qualifier),
                localViewing,
                assessment,
                weather);
    }

    static boolean isNearPerigee(double closeness) {
        return Double.isFinite(closeness) && closeness >= MINIMUM_CLOSENESS;
    }

    private static double closeness(
            double distanceAtPeak,
            double perigeeDistance,
            double apogeeDistance
    ) {
        if (!Double.isFinite(distanceAtPeak)
                || !Double.isFinite(perigeeDistance)
                || !Double.isFinite(apogeeDistance)
                || apogeeDistance <= perigeeDistance) {
            throw new IllegalStateException("Lunar distance data was not physically valid.");
        }
        double value = (apogeeDistance - distanceAtPeak)
                / (apogeeDistance - perigeeDistance);
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("Near-perigee closeness was not finite.");
        }
        return value;
    }

    private static Apsides apsidesAround(Time fullMoon) {
        Instant peakAt = instant(fullMoon);
        ApsisInfo before = Astronomy.searchLunarApsis(
                fullMoon.addDays(-APSIS_SEARCH_LOOKBACK_DAYS));
        ApsisInfo after = Astronomy.nextLunarApsis(before);
        while (instant(after.getTime()).isBefore(peakAt)) {
            before = after;
            after = Astronomy.nextLunarApsis(after);
        }
        if (instant(before.getTime()).isAfter(peakAt)) {
            throw new IllegalStateException("Could not bracket the full Moon with lunar apsides.");
        }

        ApsisInfo perigee = before.getKind() == ApsisKind.Pericenter ? before : after;
        ApsisInfo apogee = before.getKind() == ApsisKind.Apocenter ? before : after;
        if (perigee.getKind() != ApsisKind.Pericenter
                || apogee.getKind() != ApsisKind.Apocenter) {
            throw new IllegalStateException("Consecutive lunar apsides did not alternate.");
        }
        return new Apsides(perigee.getDistanceKm(), apogee.getDistanceKm());
    }

    private static Time nextFullMoon(Time startsAt) {
        Time fullMoon = Astronomy.searchMoonPhase(
                FULL_MOON_PHASE_DEGREES, startsAt, PHASE_SEARCH_LIMIT_DAYS);
        if (fullMoon == null) {
            throw new IllegalStateException("No full Moon was found inside the search limit.");
        }
        return fullMoon;
    }

    private static String stableId(Instant peakAt) {
        UUID value = UUID.nameUUIDFromBytes(
                ("full_moon:" + peakAt).getBytes(StandardCharsets.UTF_8));
        return "full-moon-" + value;
    }

    private static Time astronomyTime(Instant instant) {
        return Time.fromMillisecondsSince1970(instant.toEpochMilli());
    }

    private static Instant instant(Time time) {
        return Instant.ofEpochMilli(time.toMillisecondsSince1970());
    }

    private record Apsides(
            double perigeeDistanceKilometers,
            double apogeeDistanceKilometers
    ) {
    }
}
