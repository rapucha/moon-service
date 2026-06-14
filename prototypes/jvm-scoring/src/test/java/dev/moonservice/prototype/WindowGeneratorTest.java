package dev.moonservice.prototype;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowGeneratorTest {
    @Test
    void groupsContiguousLowMoonSamplesAndSelectsBestPeak() {
        PrototypeConfig config = PrototypeConfig.defaults();
        Instant start = config.start();
        List<MoonSample> samples = List.of(
                sample(start, -1.0, -4.0),
                sample(start.plusSeconds(1800), 0.5, -8.0),
                sample(start.plusSeconds(3600), 4.0, -4.0),
                sample(start.plusSeconds(5400), 9.0, 10.0),
                sample(start.plusSeconds(7200), 13.0, 10.0),
                sample(start.plusSeconds(9000), 6.0, -13.0)
        );

        List<MoonWindow> windows = new WindowGenerator().findWindows(samples, config);

        assertEquals(2, windows.size());
        assertEquals(start.plusSeconds(900), windows.get(0).startsAt());
        assertEquals(start.plusSeconds(3600), windows.get(0).peak().instant());
        assertEquals(start.plusSeconds(6300), windows.get(0).endsAt());
        assertEquals(3, windows.get(0).sampleCount());
        assertEquals(start.plusSeconds(9000), windows.get(1).peak().instant());
    }

    private static MoonSample sample(Instant instant, double moonAltitude, double sunAltitude) {
        return new MoonSample(instant, moonAltitude, 120.0, 90.0, sunAltitude);
    }
}
