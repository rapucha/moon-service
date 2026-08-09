package dev.moonservice.backend.opportunity.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.window.CurrentMoonCalculator;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentMoonResponse(
        String horizonState,
        OpportunitySearchResponse.Moon moon,
        OpportunitySearchResponse.Sun sun,
        @JsonInclude(JsonInclude.Include.ALWAYS) Boundary nextRiseBoundary,
        @JsonInclude(JsonInclude.Include.ALWAYS) UpcomingPass nextPass,
        @JsonInclude(JsonInclude.Include.ALWAYS) ActivePass activePass
) {
    public static CurrentMoonResponse from(CurrentMoonCalculator.Result result) {
        Objects.requireNonNull(result, "result");
        MoonSample current = result.current();
        boolean aboveOrOnHorizon = current.moonAltitudeDegrees() >= 0.0;
        return new CurrentMoonResponse(
                aboveOrOnHorizon ? "above_or_on_horizon" : "below_horizon",
                moon(current),
                sun(current),
                aboveOrOnHorizon ? null : boundary(Objects.requireNonNull(
                        result.nextRiseBoundary(), "A below-horizon result must include its next rise.")),
                nextPass(result),
                aboveOrOnHorizon ? activePass(result, current) : null);
    }

    private static ActivePass activePass(
            CurrentMoonCalculator.Result result,
            MoonSample current
    ) {
        CurrentMoonCalculator.ActivePass pass = Objects.requireNonNull(
                result.activePass(), "An above-horizon result must include its active pass.");
        MoonSample start = sampleAt(pass.pathSamples(), pass.representedStartsAt());
        MoonSample end = sampleAt(pass.pathSamples(), pass.representedEndsAt());
        List<OpportunitySearchResponse.MoonPathPoint> samples = pass.pathSamples().stream()
                .map(sample -> point(sample, sampleRole(sample.instant(), current.instant(), pass)))
                .toList();
        return new ActivePass(
                boundary(pass.startBoundary()),
                boundary(pass.endBoundary()),
                pass.representedStartsAt().toString(),
                pass.representedEndsAt().toString(),
                new Path(
                        point(start, "start"),
                        point(current, "now"),
                        point(end, "end"),
                        samples));
    }

    private static UpcomingPass nextPass(CurrentMoonCalculator.Result result) {
        CurrentMoonCalculator.NextPass pass = result.nextPass();
        if (pass == null) {
            return null;
        }
        MoonSample start = sampleAt(pass.pathSamples(), pass.representedStartsAt());
        MoonSample end = sampleAt(pass.pathSamples(), pass.representedEndsAt());
        List<OpportunitySearchResponse.MoonPathPoint> samples = pass.pathSamples().stream()
                .map(sample -> point(sample, upcomingSampleRole(sample.instant(), pass)))
                .toList();
        return new UpcomingPass(
                boundary(pass.startBoundary()),
                boundary(pass.endBoundary()),
                pass.representedStartsAt().toString(),
                pass.representedEndsAt().toString(),
                new UpcomingPath(
                        point(start, "start"),
                        point(end, "end"),
                        samples));
    }

    private static Boundary boundary(CurrentMoonCalculator.Boundary boundary) {
        String status = switch (boundary.status()) {
            case FOUND -> "found";
            case NOT_FOUND_WITHIN_RANGE -> "not_found_within_range";
        };
        return new Boundary(status, boundary.at() == null ? null : boundary.at().toString());
    }

    private static MoonSample sampleAt(List<MoonSample> samples, Instant instant) {
        return samples.stream()
                .filter(sample -> sample.instant().equals(instant))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Current Moon path has no represented endpoint sample at " + instant + "."));
    }

    private static String sampleRole(
            Instant instant,
            Instant current,
            CurrentMoonCalculator.ActivePass pass
    ) {
        if (instant.equals(current)) {
            return "now";
        }
        if (instant.equals(pass.representedStartsAt())) {
            return "start";
        }
        if (instant.equals(pass.representedEndsAt())) {
            return "end";
        }
        return "path";
    }

    private static String upcomingSampleRole(
            Instant instant,
            CurrentMoonCalculator.NextPass pass
    ) {
        if (instant.equals(pass.representedStartsAt())) {
            return "start";
        }
        if (instant.equals(pass.representedEndsAt())) {
            return "end";
        }
        return "path";
    }

    private static OpportunitySearchResponse.Moon moon(MoonSample sample) {
        return new OpportunitySearchResponse.Moon(
                round3(sample.moonAltitudeDegrees()),
                round3(sample.moonAzimuthDegrees()),
                round3(sample.moonIlluminationPercent()),
                round3(sample.moonPhaseAngleDegrees()),
                round3(sample.brightLimbTiltDegrees()),
                round3(sample.northPoleTiltDegrees()),
                phaseName(sample.moonPhaseAngleDegrees()));
    }

    private static OpportunitySearchResponse.Sun sun(MoonSample sample) {
        return new OpportunitySearchResponse.Sun(
                round3(sample.sunAltitudeDegrees()),
                round3(sample.sunAzimuthDegrees()),
                ScoringModel.lightBucket(sample.sunAltitudeDegrees()));
    }

    private static OpportunitySearchResponse.MoonPathPoint point(
            MoonSample sample,
            String role
    ) {
        return new OpportunitySearchResponse.MoonPathPoint(
                sample.instant().toString(),
                round3(sample.moonAltitudeDegrees()),
                round3(sample.moonAzimuthDegrees()),
                round3(sample.moonPhaseAngleDegrees()),
                round3(sample.brightLimbTiltDegrees()),
                round3(sample.northPoleTiltDegrees()),
                round3(sample.sunAltitudeDegrees()),
                round3(sample.sunAzimuthDegrees()),
                ScoringModel.lightBucket(sample.sunAltitudeDegrees()),
                role);
    }

    private static String phaseName(double phaseAngleDegrees) {
        double angle = ((phaseAngleDegrees % 360.0) + 360.0) % 360.0;
        if (angle < 22.5 || angle >= 337.5) {
            return "new_moon";
        }
        if (angle < 67.5) {
            return "waxing_crescent";
        }
        if (angle < 112.5) {
            return "first_quarter";
        }
        if (angle < 157.5) {
            return "waxing_gibbous";
        }
        if (angle < 202.5) {
            return "full_moon";
        }
        if (angle < 247.5) {
            return "waning_gibbous";
        }
        if (angle < 292.5) {
            return "last_quarter";
        }
        return "waning_crescent";
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static Double round3(Double value) {
        return value == null ? null : round3(value.doubleValue());
    }

    public record ActivePass(
            Boundary startBoundary,
            Boundary endBoundary,
            String representedStartsAt,
            String representedEndsAt,
            Path path
    ) {
    }

    public record UpcomingPass(
            Boundary startBoundary,
            Boundary endBoundary,
            String representedStartsAt,
            String representedEndsAt,
            UpcomingPath path
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Boundary(String status, String at) {
    }

    public record Path(
            OpportunitySearchResponse.MoonPathPoint start,
            OpportunitySearchResponse.MoonPathPoint now,
            OpportunitySearchResponse.MoonPathPoint end,
            List<OpportunitySearchResponse.MoonPathPoint> samples
    ) {
    }

    public record UpcomingPath(
            OpportunitySearchResponse.MoonPathPoint start,
            OpportunitySearchResponse.MoonPathPoint end,
            List<OpportunitySearchResponse.MoonPathPoint> samples
    ) {
    }
}
