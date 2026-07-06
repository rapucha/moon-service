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
import io.github.cosinekitty.astronomy.Time;
import io.github.cosinekitty.astronomy.Topocentric;

import java.time.Instant;

public final class EphemerisSampler {
    public MoonSample sampleAt(Location location, Instant instant) {
        Observer observer = new Observer(location.latitude(), location.longitude(), location.elevationMeters());
        Time time = Time.fromMillisecondsSince1970(instant.toEpochMilli());

        Topocentric moon = horizon(Body.Moon, time, observer);
        Topocentric sun = horizon(Body.Sun, time, observer);
        IlluminationInfo illumination = Astronomy.illumination(Body.Moon, time);

        return new MoonSample(
                instant,
                moon.getAltitude(),
                moon.getAzimuth(),
                100.0 * illumination.getPhaseFraction(),
                Astronomy.moonPhase(time),
                sun.getAltitude(),
                sun.getAzimuth()
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
}
