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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MoonWindowPrototype {
    private static final Location PRAGUE = new Location(
            "prague-cz",
            "Prague / Praha, Czech Republic",
            50.08804,
            14.42076,
            202.0,
            "Europe/Prague"
    );

    private static final int DEFAULT_DAYS = 7;
    private static final int DEFAULT_STEP_MINUTES = 30;
    private static final double DEFAULT_MAX_MOON_ALTITUDE = 12.0;

    public static void main(String[] args) {
        try {
            System.out.println(run(Config.parse(args)));
        } catch (UsageException ex) {
            System.err.println(ex.getMessage());
            System.err.println(usage());
            System.exit(2);
        }
    }

    private static String run(Config config) {
        List<MoonSample> samples = sample(config);
        List<MoonWindow> windows = findWindows(samples, config);
        windows.sort(Comparator.comparingDouble(MoonWindow::scoreSortKey).reversed()
                .thenComparing(window -> window.peak.instant));
        if (windows.size() > config.limit) {
            windows = windows.subList(0, config.limit);
        }

        Json out = new Json();
        out.line("{");
        out.field("status", "ok", true);
        out.field("prototype", "jvm_ephemeris_astronomy_engine", true);
        out.field("ephemerisSource", "Astronomy Engine 2.1.19 via JitPack", true);
        out.field("generatedAt", Instant.now().toString(), true);
        out.line("\"location\": {");
        out.field("id", config.location.id, true);
        out.field("displayName", config.location.displayName, true);
        out.field("latitude", config.location.latitude, 5, true);
        out.field("longitude", config.location.longitude, 5, true);
        out.field("elevationMeters", config.location.elevationMeters, 1, true);
        out.field("timezone", config.location.timezone, false);
        out.line("},");
        out.field("startsAt", config.start.toString(), true);
        out.field("endsAt", config.end().toString(), true);
        out.field("sampleStepMinutes", config.stepMinutes, true);
        out.field("samplesEvaluated", samples.size(), true);
        out.field("maxMoonAltitudeDegrees", config.maxMoonAltitudeDegrees, true);
        out.line("\"opportunities\": [");
        for (int i = 0; i < windows.size(); i++) {
            windows.get(i).writeJson(out, i < windows.size() - 1);
        }
        out.line("],");
        out.line("\"diagnostics\": {");
        out.field("note", "Prototype only: no weather, scoring persistence, HTTP API, database, or backend framework.", true);
        out.field("selectionRule", "Contiguous samples where the apparent refracted Moon altitude is between 0 degrees and the configured maximum.", false);
        out.line("}");
        out.line("}");
        return out.toString();
    }

    private static List<MoonSample> sample(Config config) {
        List<MoonSample> samples = new ArrayList<>();
        Instant cursor = config.start;
        Instant end = config.end();
        while (!cursor.isAfter(end)) {
            samples.add(sampleAt(config.location, cursor));
            cursor = cursor.plus(Duration.ofMinutes(config.stepMinutes));
        }
        return samples;
    }

    private static MoonSample sampleAt(Location location, Instant instant) {
        Observer observer = new Observer(location.latitude, location.longitude, location.elevationMeters);
        Time time = Time.fromMillisecondsSince1970(instant.toEpochMilli());

        Topocentric moon = horizon(Body.Moon, time, observer);
        Topocentric sun = horizon(Body.Sun, time, observer);
        IlluminationInfo illumination = Astronomy.illumination(Body.Moon, time);

        return new MoonSample(
                instant,
                moon.getAltitude(),
                moon.getAzimuth(),
                100.0 * illumination.getPhaseFraction(),
                sun.getAltitude()
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

    private static List<MoonWindow> findWindows(List<MoonSample> samples, Config config) {
        List<MoonWindow> windows = new ArrayList<>();
        List<MoonSample> current = new ArrayList<>();

        for (MoonSample sample : samples) {
            if (sample.moonAltitudeDegrees >= 0.0 && sample.moonAltitudeDegrees <= config.maxMoonAltitudeDegrees) {
                current.add(sample);
            } else {
                flushWindow(windows, current, config);
            }
        }
        flushWindow(windows, current, config);
        return windows;
    }

    private static void flushWindow(List<MoonWindow> windows, List<MoonSample> samples, Config config) {
        if (samples.isEmpty()) {
            return;
        }
        MoonSample peak = samples.stream()
                .max(Comparator.comparingDouble(MoonSample::candidateFit))
                .orElseThrow();
        Duration halfStep = Duration.ofMinutes(config.stepMinutes / 2L);
        Instant startsAt = max(config.start, samples.get(0).instant.minus(halfStep));
        Instant endsAt = min(config.end(), samples.get(samples.size() - 1).instant.plus(halfStep));
        windows.add(new MoonWindow(config.location.id, startsAt, peak, endsAt, samples.size()));
        samples.clear();
    }

    private static Instant max(Instant a, Instant b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String lightBucket(double sunAltitude) {
        if (sunAltitude >= 6.0) {
            return "daylight";
        }
        if (sunAltitude >= -0.833) {
            return "golden_hour";
        }
        if (sunAltitude >= -6.0) {
            return "civil_twilight";
        }
        if (sunAltitude >= -12.0) {
            return "nautical_twilight";
        }
        return "night";
    }

    private static String exposureBalance(MoonSample sample) {
        String bucket = lightBucket(sample.sunAltitudeDegrees);
        if (bucket.equals("daylight") || bucket.equals("golden_hour")) {
            if (sample.moonIlluminationPercent >= 70.0) {
                return "moon_detail_easy_foreground_supported";
            }
            if (sample.moonIlluminationPercent < 5.0) {
                return "thin_crescent_visible_but_subtle";
            }
            return "balanced";
        }
        if (bucket.equals("civil_twilight")) {
            if (sample.moonIlluminationPercent >= 85.0) {
                return "moon_bright_foreground_risk";
            }
            if (sample.moonIlluminationPercent < 5.0) {
                return "thin_crescent_visible_but_subtle";
            }
            return "balanced";
        }
        if (sample.moonIlluminationPercent >= 70.0) {
            return "moon_bright_foreground_risk";
        }
        return "foreground_likely_dark";
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage:",
                "  java -cp /tmp/astronomy-2.1.19.jar:/tmp/kotlin-stdlib-jdk8-1.6.10.jar:/tmp/kotlin-stdlib-jdk7-1.6.10.jar:/tmp/kotlin-stdlib-1.6.10.jar:/tmp/kotlin-stdlib-common-1.6.10.jar \\",
                "    prototypes/jvm-ephemeris/MoonWindowPrototype.java [options]",
                "",
                "Options:",
                "  --location prague-cz         Fixture location; only prague-cz exists in this prototype.",
                "  --start YYYY-MM-DD|INSTANT  UTC start date or instant. Default: 2026-06-29.",
                "  --days N                    Days to sample. Default: 7.",
                "  --step-minutes N            Sampling step. Default: 30.",
                "  --max-altitude DEG          Low-Moon ceiling. Default: 12.",
                "  --limit N                   Maximum returned windows. Default: 10."
        );
    }

    private record Location(
            String id,
            String displayName,
            double latitude,
            double longitude,
            double elevationMeters,
            String timezone
    ) {
    }

    private record Config(
            Location location,
            Instant start,
            int days,
            int stepMinutes,
            double maxMoonAltitudeDegrees,
            int limit
    ) {
        Instant end() {
            return start.plus(Duration.ofDays(days));
        }

        static Config parse(String[] args) {
            String locationId = "prague-cz";
            Instant start = LocalDate.parse("2026-06-29").atStartOfDay().toInstant(ZoneOffset.UTC);
            int days = DEFAULT_DAYS;
            int stepMinutes = DEFAULT_STEP_MINUTES;
            double maxMoonAltitudeDegrees = DEFAULT_MAX_MOON_ALTITUDE;
            int limit = 10;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String value = requireValue(args, ++i, arg);
                switch (arg) {
                    case "--location" -> locationId = value;
                    case "--start" -> start = parseStart(value);
                    case "--days" -> days = parseInt(arg, value, 1, 30);
                    case "--step-minutes" -> stepMinutes = parseInt(arg, value, 1, 180);
                    case "--max-altitude" -> maxMoonAltitudeDegrees = parseDouble(arg, value, 0.0, 45.0);
                    case "--limit" -> limit = parseInt(arg, value, 1, 100);
                    default -> throw new UsageException("Unknown option: " + arg);
                }
            }

            if (!locationId.equals(PRAGUE.id)) {
                throw new UsageException("Unsupported location for this prototype: " + locationId);
            }
            return new Config(PRAGUE, start, days, stepMinutes, maxMoonAltitudeDegrees, limit);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new UsageException("Missing value for " + option);
            }
            return args[index];
        }

        private static Instant parseStart(String value) {
            try {
                if (value.length() == 10) {
                    return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
                }
                return Instant.parse(value);
            } catch (DateTimeParseException ex) {
                throw new UsageException("Invalid --start value: " + value);
            }
        }

        private static int parseInt(String option, String value, int min, int max) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < min || parsed > max) {
                    throw new UsageException(option + " must be between " + min + " and " + max);
                }
                return parsed;
            } catch (NumberFormatException ex) {
                throw new UsageException(option + " must be an integer: " + value);
            }
        }

        private static double parseDouble(String option, String value, double min, double max) {
            try {
                double parsed = Double.parseDouble(value);
                if (!Double.isFinite(parsed) || parsed < min || parsed > max) {
                    throw new UsageException(option + " must be between " + min + " and " + max);
                }
                return parsed;
            } catch (NumberFormatException ex) {
                throw new UsageException(option + " must be numeric: " + value);
            }
        }
    }

    private record MoonSample(
            Instant instant,
            double moonAltitudeDegrees,
            double moonAzimuthDegrees,
            double moonIlluminationPercent,
            double sunAltitudeDegrees
    ) {
        double candidateFit() {
            double altitudeFit = moonAltitudeDegrees >= 1.0 && moonAltitudeDegrees <= 6.0
                    ? 30.0
                    : Math.max(0.0, 30.0 - Math.abs(moonAltitudeDegrees - 4.0) * 3.0);
            double lightFit = switch (lightBucket(sunAltitudeDegrees)) {
                case "golden_hour" -> 25.0;
                case "civil_twilight" -> 24.0;
                case "daylight" -> 16.0;
                case "nautical_twilight" -> 14.0;
                default -> 7.0;
            };
            return altitudeFit + lightFit;
        }
    }

    private record MoonWindow(
            String locationId,
            Instant startsAt,
            MoonSample peak,
            Instant endsAt,
            int sampleCount
    ) {
        double scoreSortKey() {
            return peak.candidateFit();
        }

        void writeJson(Json out, boolean comma) {
            String id = locationId + "-" + peak.instant.toString()
                    .replace(":", "")
                    .replace("-", "")
                    .replace(".000", "");
            out.line("{");
            out.field("id", id, true);
            out.field("startsAt", startsAt.toString(), true);
            out.field("peaksAt", peak.instant.toString(), true);
            out.field("endsAt", endsAt.toString(), true);
            out.field("sampleCount", sampleCount, true);
            out.line("\"moon\": {");
            out.field("altitudeDegrees", round3(peak.moonAltitudeDegrees), true);
            out.field("azimuthDegrees", round3(peak.moonAzimuthDegrees), true);
            out.field("illuminationPercent", round3(peak.moonIlluminationPercent), false);
            out.line("},");
            out.line("\"sun\": {");
            out.field("altitudeDegrees", round3(peak.sunAltitudeDegrees), true);
            out.field("lightBucket", lightBucket(peak.sunAltitudeDegrees), false);
            out.line("},");
            out.line("\"exposureBalance\": {");
            out.field("label", exposureBalance(peak), true);
            out.field("text", exposureText(peak), false);
            out.line("}");
            out.line(comma ? "}," : "}");
        }

        private static String exposureText(MoonSample sample) {
            return switch (exposureBalance(sample)) {
                case "moon_detail_easy_foreground_supported" ->
                        "Ambient light should support foreground detail; expose carefully for the bright Moon.";
                case "thin_crescent_visible_but_subtle" ->
                        "Ambient light may help the scene, but the thin crescent may be subtle.";
                case "moon_bright_foreground_risk" ->
                        "The Moon is bright while foreground light is limited; foreground detail may need careful exposure.";
                case "foreground_likely_dark" ->
                        "Foreground detail is likely limited without silhouette intent, artificial light, or blending.";
                default ->
                        "Ambient light and Moon brightness look reasonably balanced for a natural exposure.";
            };
        }
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private static final class Json {
        private final StringBuilder builder = new StringBuilder();
        private int indent = 0;

        void line(String value) {
            if (value.equals("}") || value.equals("},") || value.equals("]") || value.equals("],")) {
                indent--;
            }
            builder.append("  ".repeat(Math.max(0, indent))).append(value).append(System.lineSeparator());
            if (value.endsWith("{") || value.endsWith("[")) {
                indent++;
            }
        }

        void field(String name, String value, boolean comma) {
            line("\"" + name + "\": \"" + escape(value) + "\"" + (comma ? "," : ""));
        }

        void field(String name, double value, boolean comma) {
            line("\"" + name + "\": " + String.format(Locale.ROOT, "%.3f", value) + (comma ? "," : ""));
        }

        void field(String name, double value, int decimals, boolean comma) {
            line("\"" + name + "\": " + String.format(Locale.ROOT, "%." + decimals + "f", value) + (comma ? "," : ""));
        }

        void field(String name, int value, boolean comma) {
            line("\"" + name + "\": " + value + (comma ? "," : ""));
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        @Override
        public String toString() {
            return builder.toString();
        }
    }
}
