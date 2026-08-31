package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AzimuthPreference;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.LocalClockWindow;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimePreference;
import dev.moonservice.scoringprototype.scoring.ScoringModel;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

public final class Version1PreferenceMatcher {
    private Version1PreferenceMatcher() {
    }

    static boolean matchesAll(
            Location location,
            MoonSample sample,
            ToDoubleFunction<Instant> lunarRadii,
            OpportunityPreferences preferences
    ) {
        if (preferences.altitudeDegrees() != null
                && !matchesAltitude(sample, preferences.altitudeDegrees())) {
            return false;
        }
        if (preferences.azimuthDegrees() != null
                && !matchesAzimuth(sample, lunarRadii.applyAsDouble(sample.instant()),
                preferences.azimuthDegrees())) {
            return false;
        }
        if (preferences.time() != null && !matchesTime(location, sample, preferences.time())) {
            return false;
        }
        NamedPhase phase = NamedPhase.fromPhaseAngleDegrees(sample.moonPhaseAngleDegrees());
        if (preferences.namedPhases() != null
                && !matchesNamedPhase(phase, preferences.namedPhases())) {
            return false;
        }
        return preferences.brightLimbOrientationDegrees() == null
                || matchesBrightLimb(sample, phase, preferences.brightLimbOrientationDegrees());
    }

    public static boolean matchesAltitude(MoonSample sample, AltitudeRange preference) {
        return sample.moonAltitudeDegrees() >= preference.minimum()
                && sample.moonAltitudeDegrees() <= preference.maximum();
    }

    public static boolean matchesAzimuth(
            MoonSample sample,
            double radiusDegrees,
            AzimuthPreference preference
    ) {
        if (!Double.isFinite(radiusDegrees) || radiusDegrees <= 0.0 || radiusDegrees >= 90.0) {
            throw new IllegalArgumentException(
                    "Lunar angular radius must be finite and between 0 and 90 degrees.");
        }
        List<BearingInterval> allowed = preference.included() == null
                ? List.of(new BearingInterval(0.0, 360.0))
                : segments(preference.included());
        if (preference.excluded() != null) {
            for (BearingInterval excluded : segments(preference.excluded())) {
                allowed = subtract(allowed, excluded);
            }
        }
        if (allowed.isEmpty()) {
            return false;
        }
        List<BearingInterval> allowedSegments = allowed;
        double altitude = Math.toRadians(sample.moonAltitudeDegrees());
        double radius = Math.toRadians(radiusDegrees);
        double horizontalScale = Math.abs(Math.cos(altitude));
        if (horizontalScale < Math.sin(radius)) {
            return allowedSegments.stream().anyMatch(interval -> interval.length() > 0.0);
        }
        double halfWidth = Math.toDegrees(Math.asin(Math.sin(radius) / horizontalScale));
        return footprint(sample.moonAzimuthDegrees(), halfWidth).stream().anyMatch(disk ->
                allowedSegments.stream().anyMatch(sector -> overlapLength(disk, sector) > 0.0));
    }

    public static boolean matchesTime(
            Location location,
            MoonSample sample,
            TimePreference preference
    ) {
        if (preference.mode() == TimeMode.LIGHT_BUCKET) {
            String bucket = ScoringModel.lightBucket(sample.sunAltitudeDegrees());
            return preference.lightBuckets().stream()
                    .map(AmbientLight::wireValue)
                    .anyMatch(bucket::equals);
        }
        LocalTime local = sample.instant().atZone(location.zoneId()).toLocalTime();
        LocalClockWindow window = preference.localClockWindow();
        return window.start().isBefore(window.end())
                ? !local.isBefore(window.start()) && local.isBefore(window.end())
                : !local.isBefore(window.start()) || local.isBefore(window.end());
    }

    public static boolean matchesNamedPhase(NamedPhase phase, Set<NamedPhase> preferences) {
        return preferences.contains(phase);
    }

    private static boolean matchesBrightLimb(
            MoonSample sample,
            NamedPhase phase,
            List<DegreeRange> preferences
    ) {
        Double tilt = phase == NamedPhase.FULL_MOON ? null : sample.brightLimbTiltDegrees();
        return tilt != null && preferences.stream().anyMatch(range -> range.contains(tilt));
    }

    private static List<BearingInterval> segments(DegreeRange range) {
        return range.start() < range.end()
                ? List.of(new BearingInterval(range.start(), range.end()))
                : List.of(new BearingInterval(0.0, range.end()),
                        new BearingInterval(range.start(), 360.0));
    }

    private static List<BearingInterval> footprint(double azimuth, double halfWidth) {
        double center = normalize(azimuth);
        double start = center - halfWidth;
        double end = center + halfWidth;
        if (start < 0.0) {
            return List.of(new BearingInterval(0.0, end),
                    new BearingInterval(start + 360.0, 360.0));
        }
        if (end > 360.0) {
            return List.of(new BearingInterval(0.0, end - 360.0),
                    new BearingInterval(start, 360.0));
        }
        return List.of(new BearingInterval(start, end));
    }

    private static List<BearingInterval> subtract(
            List<BearingInterval> source,
            BearingInterval removed
    ) {
        List<BearingInterval> result = new ArrayList<>();
        for (BearingInterval current : source) {
            if (removed.end() <= current.start() || removed.start() >= current.end()) {
                result.add(current);
            } else {
                if (removed.start() > current.start()) {
                    result.add(new BearingInterval(current.start(), Math.min(removed.start(), current.end())));
                }
                if (removed.end() < current.end()) {
                    result.add(new BearingInterval(Math.max(removed.end(), current.start()), current.end()));
                }
            }
        }
        return result;
    }

    private static double overlapLength(BearingInterval left, BearingInterval right) {
        return Math.max(0.0, Math.min(left.end(), right.end()) - Math.max(left.start(), right.start()));
    }

    private static double normalize(double value) {
        return ((value % 360.0) + 360.0) % 360.0;
    }

    private record BearingInterval(double start, double end) {
        double length() {
            return end - start;
        }
    }
}
