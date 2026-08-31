package dev.moonservice.scoringprototype.ephemeris;

import java.util.Objects;

public record LunarEclipseShadowSample(
        MoonSample moon,
        double centerRightMoonRadii,
        double centerUpMoonRadii,
        double umbraRadiusMoonRadii,
        double penumbraRadiusMoonRadii
) {
    public LunarEclipseShadowSample {
        Objects.requireNonNull(moon, "moon");
        if (!Double.isFinite(centerRightMoonRadii)
                || !Double.isFinite(centerUpMoonRadii)
                || !Double.isFinite(umbraRadiusMoonRadii)
                || !Double.isFinite(penumbraRadiusMoonRadii)
                || umbraRadiusMoonRadii <= 0.0
                || penumbraRadiusMoonRadii <= umbraRadiusMoonRadii) {
            throw new IllegalArgumentException("Lunar eclipse shadow geometry must be finite and ordered.");
        }
    }
}
