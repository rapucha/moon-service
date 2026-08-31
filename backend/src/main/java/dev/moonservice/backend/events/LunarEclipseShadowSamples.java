package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.EclipseShadow;
import dev.moonservice.backend.events.MoonEventResponse.EclipseShadowMoon;
import dev.moonservice.backend.events.MoonEventResponse.EclipseShadowSample;
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.LunarEclipseShadowSample;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.window.RefinedTimeGrid;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

final class LunarEclipseShadowSamples {
    private final EphemerisSampler ephemeris;

    LunarEclipseShadowSamples(EphemerisSampler ephemeris) {
        this.ephemeris = Objects.requireNonNull(ephemeris, "ephemeris");
    }

    List<EclipseShadowSample> sample(
            Location location,
            List<RefinedTimeGrid.Interval> phases,
            Instant maximumAt,
            Instant suggestedAt
    ) {
        TreeSet<Instant> instants = new TreeSet<>();
        phases.forEach(phase -> {
            instants.add(phase.startsAt());
            instants.add(phase.endsAt());
        });
        instants.add(maximumAt);
        instants.add(suggestedAt);
        return instants.stream()
                .map(instant -> ephemeris.lunarEclipseShadowAt(location, instant))
                .map(LunarEclipseShadowSamples::responseSample)
                .toList();
    }

    private static EclipseShadowSample responseSample(LunarEclipseShadowSample sample) {
        MoonSample moon = sample.moon();
        return new EclipseShadowSample(
                moon.instant().toString(),
                new EclipseShadowMoon(
                        moon.moonAltitudeDegrees(),
                        moon.moonAzimuthDegrees(),
                        moon.northPoleTiltDegrees()),
                new EclipseShadow(
                        sample.centerRightMoonRadii(),
                        sample.centerUpMoonRadii(),
                        sample.umbraRadiusMoonRadii(),
                        sample.penumbraRadiusMoonRadii()));
    }
}
