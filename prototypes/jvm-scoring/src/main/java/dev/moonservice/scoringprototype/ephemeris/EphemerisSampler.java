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
    private static final double POLE_PROJECTION_EPSILON = 1.0e-12;

    public MoonSample sampleAt(Location location, Instant instant) {
        Observer observer = new Observer(location.latitude(), location.longitude(), location.elevationMeters());
        Time time = Time.fromMillisecondsSince1970(instant.toEpochMilli());

        Equatorial moonEquatorial = Astronomy.equator(
                Body.Moon,
                time,
                observer,
                EquatorEpoch.J2000,
                Aberration.Corrected
        );
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

    private static Double northPoleTiltDegrees(Time time, Observer observer, Vector moonEquatorialJ2000) {
        RotationMatrix toHorizontal = Astronomy.rotationEqjHor(time, observer);
        Vector moon = toHorizontal.rotate(moonEquatorialJ2000);
        Vector northPole = toHorizontal.rotate(Astronomy.rotationAxis(Body.Moon, time).getNorth());
        if (!finite(moon) || !finite(northPole)) {
            return null;
        }

        double moonLength = moon.length();
        if (!Double.isFinite(moonLength) || moonLength <= POLE_PROJECTION_EPSILON) {
            return null;
        }
        double moonNorth = moon.getX() / moonLength;
        double moonWest = moon.getY() / moonLength;
        double moonUp = moon.getZ() / moonLength;
        double moonHorizontal = Math.hypot(moonNorth, moonWest);
        if (moonHorizontal <= POLE_PROJECTION_EPSILON) {
            return null;
        }

        double rightNorth = moonWest / moonHorizontal;
        double rightWest = -moonNorth / moonHorizontal;
        double upNorth = -moonUp * moonNorth / moonHorizontal;
        double upWest = -moonUp * moonWest / moonHorizontal;
        double upZenith = moonHorizontal;
        double poleRight = northPole.getX() * rightNorth + northPole.getY() * rightWest;
        double poleUp = northPole.getX() * upNorth
                + northPole.getY() * upWest
                + northPole.getZ() * upZenith;
        if (!Double.isFinite(poleRight)
                || !Double.isFinite(poleUp)
                || Math.hypot(poleRight, poleUp) <= POLE_PROJECTION_EPSILON) {
            return null;
        }

        return normalizeDegrees(Math.toDegrees(Math.atan2(poleRight, poleUp)));
    }

    private static boolean finite(Vector vector) {
        return Double.isFinite(vector.getX())
                && Double.isFinite(vector.getY())
                && Double.isFinite(vector.getZ());
    }

    private static double normalizeDegrees(double value) {
        return ((value % 360.0) + 360.0) % 360.0;
    }
}
