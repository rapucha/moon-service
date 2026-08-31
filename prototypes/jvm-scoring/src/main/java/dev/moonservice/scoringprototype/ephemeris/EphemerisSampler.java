package dev.moonservice.scoringprototype.ephemeris;

import dev.moonservice.scoringprototype.fixture.Location;
import io.github.cosinekitty.astronomy.Aberration;
import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.Body;
import io.github.cosinekitty.astronomy.EquatorEpoch;
import io.github.cosinekitty.astronomy.Equatorial;
import io.github.cosinekitty.astronomy.IlluminationInfo;
import io.github.cosinekitty.astronomy.Observer;
import io.github.cosinekitty.astronomy.Refraction;
import io.github.cosinekitty.astronomy.RotationMatrix;
import io.github.cosinekitty.astronomy.Time;
import io.github.cosinekitty.astronomy.Topocentric;
import io.github.cosinekitty.astronomy.Vector;

import java.time.Instant;

public final class EphemerisSampler {
    private static final double SUN_RADIUS_KM = 695_700.0;
    private static final double EARTH_MEAN_RADIUS_KM = 6_371.0;
    private static final double EARTH_ECLIPSE_ATMOSPHERE_KM = 88.0;
    private static final double EARTH_ECLIPSE_RADIUS_KM =
            EARTH_MEAN_RADIUS_KM + EARTH_ECLIPSE_ATMOSPHERE_KM;
    private static final double MOON_MEAN_RADIUS_KM = 1_737.4;
    private static final double PROJECTION_EPSILON = 1.0e-12;

    public MoonSample sampleAt(Location location, Instant instant) {
        Observer observer = observer(location);
        Time time = time(instant);
        Equatorial moon = moonEquatorial(time, observer);
        return sampleAt(instant, time, observer, moon);
    }

    public double topocentricLunarAngularRadiusDegrees(Location location, Instant instant) {
        Equatorial moon = moonEquatorial(time(instant), observer(location));
        double distanceKilometers = moon.getDist() * Astronomy.KM_PER_AU;
        if (!Double.isFinite(distanceKilometers) || distanceKilometers <= MOON_MEAN_RADIUS_KM) {
            throw new IllegalStateException("Topocentric Moon distance was not physically valid.");
        }
        return Math.toDegrees(Math.asin(MOON_MEAN_RADIUS_KM / distanceKilometers));
    }

    public LunarEclipseShadowSample lunarEclipseShadowAt(Location location, Instant instant) {
        Observer observer = observer(location);
        Time time = time(instant);
        Equatorial topocentricMoon = moonEquatorial(time, observer);
        MoonSample moonSample = sampleAt(instant, time, observer, topocentricMoon);
        // Astronomy Engine exposes eclipse contacts, but its drawable shadow geometry is
        // internal. Derive that geometry from supported public vectors instead of binding
        // Moon Service to Kotlin implementation details.
        Vector sun = Astronomy.geoVector(Body.Sun, time, Aberration.Corrected);
        Vector moon = Astronomy.geoMoon(time);
        double directionX = -sun.getX();
        double directionY = -sun.getY();
        double directionZ = -sun.getZ();
        double directionSquared = directionX * directionX
                + directionY * directionY
                + directionZ * directionZ;
        if (!finite(sun) || !finite(moon)
                || !Double.isFinite(directionSquared)
                || directionSquared <= PROJECTION_EPSILON) {
            throw new IllegalStateException("Earth-shadow axis was not finite.");
        }

        // Keep the cone geocentric so the observer cannot change the physical eclipse.
        // This projection locates the shadow axis beside the Moon; offset points from the
        // Moon center to that axis.
        double projection = (directionX * moon.getX()
                + directionY * moon.getY()
                + directionZ * moon.getZ()) / directionSquared;
        if (!Double.isFinite(projection) || projection <= 0.0) {
            throw new IllegalStateException("The Moon was not behind Earth relative to the Sun.");
        }
        Vector offset = new Vector(
                projection * directionX - moon.getX(),
                projection * directionY - moon.getY(),
                projection * directionZ - moon.getZ(),
                time
        );
        double umbraRadiusKilometers = EARTH_ECLIPSE_RADIUS_KM
                - projection * (SUN_RADIUS_KM - EARTH_ECLIPSE_RADIUS_KM);
        double penumbraRadiusKilometers = EARTH_ECLIPSE_RADIUS_KM
                + projection * (SUN_RADIUS_KM + EARTH_ECLIPSE_RADIUS_KM);
        if (!finite(offset)
                || !Double.isFinite(umbraRadiusKilometers)
                || !Double.isFinite(penumbraRadiusKilometers)
                || umbraRadiusKilometers <= 0.0
                || penumbraRadiusKilometers <= umbraRadiusKilometers) {
            throw new IllegalStateException("Earth-shadow cone was not physically valid.");
        }

        // Observer coordinates affect presentation only. Rotate into the local screen
        // basis, then report offsets and shadow sizes in Moon-radius units for rendering.
        RotationMatrix toHorizontal = Astronomy.rotationEqjHor(time, observer);
        Vector moonHorizontal = toHorizontal.rotate(topocentricMoon.getVec());
        Vector offsetHorizontal = toHorizontal.rotate(offset);
        TangentPlane plane = tangentPlane(
                moonHorizontal, moonSample.moonAzimuthDegrees());
        if (plane == null || !finite(offsetHorizontal)) {
            throw new IllegalStateException("Earth-shadow screen projection was undefined.");
        }
        double moonRadiiPerAu = Astronomy.KM_PER_AU / MOON_MEAN_RADIUS_KM;
        return new LunarEclipseShadowSample(
                moonSample,
                moonRadiiPerAu * plane.right(offsetHorizontal),
                moonRadiiPerAu * plane.up(offsetHorizontal),
                umbraRadiusKilometers / MOON_MEAN_RADIUS_KM,
                penumbraRadiusKilometers / MOON_MEAN_RADIUS_KM
        );
    }

    private static MoonSample sampleAt(
            Instant instant,
            Time time,
            Observer observer,
            Equatorial moonEquatorial
    ) {
        Topocentric moon = horizon(time, observer, moonEquatorial);
        Topocentric sun = horizon(Body.Sun, time, observer);
        IlluminationInfo illumination = Astronomy.illumination(Body.Moon, time);
        return new MoonSample(
                instant,
                moon.getAltitude(),
                moon.getAzimuth(),
                100.0 * illumination.getPhaseFraction(),
                Astronomy.moonPhase(time),
                northPoleTiltDegrees(time, observer, moonEquatorial.getVec()),
                sun.getAltitude(),
                sun.getAzimuth()
        );
    }

    private static Equatorial moonEquatorial(Time time, Observer observer) {
        return Astronomy.equator(
                Body.Moon,
                time,
                observer,
                EquatorEpoch.J2000,
                Aberration.Corrected
        );
    }

    private static Observer observer(Location location) {
        return new Observer(
                location.latitude(), location.longitude(), location.elevationMeters());
    }

    private static Time time(Instant instant) {
        return Time.fromMillisecondsSince1970(instant.toEpochMilli());
    }

    private static Topocentric horizon(Time time, Observer observer, Equatorial equatorialJ2000) {
        Equatorial equatorialOfDate = Astronomy.rotationEqjEqd(time)
                .rotate(equatorialJ2000.getVec())
                .toEquatorial();
        return Astronomy.horizon(
                time,
                observer,
                equatorialOfDate.getRa(),
                equatorialOfDate.getDec(),
                Refraction.Normal
        );
    }

    private static Topocentric horizon(Body body, Time time, Observer observer) {
        Equatorial equatorial = Astronomy.equator(
                body,
                time,
                observer,
                EquatorEpoch.OfDate,
                Aberration.Corrected
        );
        return Astronomy.horizon(
                time,
                observer,
                equatorial.getRa(),
                equatorial.getDec(),
                Refraction.Normal
        );
    }

    private static Double northPoleTiltDegrees(
            Time time,
            Observer observer,
            Vector moonEquatorialJ2000
    ) {
        RotationMatrix toHorizontal = Astronomy.rotationEqjHor(time, observer);
        TangentPlane plane = tangentPlane(toHorizontal.rotate(moonEquatorialJ2000), null);
        Vector northPole = toHorizontal.rotate(
                Astronomy.rotationAxis(Body.Moon, time).getNorth());
        if (plane == null || !finite(northPole)) {
            return null;
        }
        double poleRight = plane.right(northPole);
        double poleUp = plane.up(northPole);
        if (!Double.isFinite(poleRight)
                || !Double.isFinite(poleUp)
                || Math.hypot(poleRight, poleUp) <= PROJECTION_EPSILON) {
            return null;
        }
        return normalizeDegrees(Math.toDegrees(Math.atan2(poleRight, poleUp)));
    }

    private static TangentPlane tangentPlane(
            Vector moon,
            Double fallbackAzimuthDegrees
    ) {
        if (!finite(moon)) {
            return null;
        }
        double length = moon.length();
        if (!Double.isFinite(length) || length <= PROJECTION_EPSILON) {
            return null;
        }
        double north = moon.getX() / length;
        double west = moon.getY() / length;
        double up = moon.getZ() / length;
        double horizontal = Math.hypot(north, west);
        if (horizontal > PROJECTION_EPSILON) {
            return new TangentPlane(
                    west / horizontal,
                    -north / horizontal,
                    -up * north / horizontal,
                    -up * west / horizontal,
                    horizontal
            );
        }
        if (fallbackAzimuthDegrees == null
                || !Double.isFinite(fallbackAzimuthDegrees)) {
            return null;
        }
        double azimuth = Math.toRadians(fallbackAzimuthDegrees);
        return new TangentPlane(
                -Math.sin(azimuth),
                -Math.cos(azimuth),
                -up * Math.cos(azimuth),
                up * Math.sin(azimuth),
                0.0
        );
    }

    private static boolean finite(Vector vector) {
        return Double.isFinite(vector.getX())
                && Double.isFinite(vector.getY())
                && Double.isFinite(vector.getZ());
    }

    private static double normalizeDegrees(double value) {
        return ((value % 360.0) + 360.0) % 360.0;
    }

    private record TangentPlane(
            double rightNorth,
            double rightWest,
            double upNorth,
            double upWest,
            double upZenith
    ) {
        double right(Vector vector) {
            return vector.getX() * rightNorth + vector.getY() * rightWest;
        }

        double up(Vector vector) {
            return vector.getX() * upNorth
                    + vector.getY() * upWest
                    + vector.getZ() * upZenith;
        }
    }
}
