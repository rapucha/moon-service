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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MoonWindowPrototype {
    private static final Location PRAGUE = new Location(
            "prague-cz",
            "real_location",
            "openmeteo:prague-cz",
            "Prague / Praha, Czech Republic",
            50.08804,
            14.42076,
            202.0,
            "Europe/Prague",
            "CZ"
    );

    private static final int DEFAULT_DAYS = 7;
    private static final int DEFAULT_STEP_MINUTES = 5;
    private static final int DEFAULT_MIN_SCORE = 50;
    private static final double DEFAULT_MAX_MOON_ALTITUDE = 12.0;
    private static final WeatherFixture FIXTURE_WEATHER = new WeatherFixture(
            35,
            10,
            25,
            40,
            5,
            0.0,
            20000,
            2,
            1.0
    );

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
        List<ScoredWindow> scored = new ArrayList<>();
        List<RejectedWindow> rejected = new ArrayList<>();
        Map<String, Integer> rejectedCounts = new LinkedHashMap<>();

        for (MoonWindow window : windows) {
            ComponentScores components = scoreWindow(window, FIXTURE_WEATHER);
            if (components.total() < config.minScore) {
                countRejections(rejectedCounts, List.of("below_minimum_score"));
                rejected.add(new RejectedWindow(window.id(), components.total(), List.of("below_minimum_score")));
                continue;
            }

            scored.add(new ScoredWindow(window, FIXTURE_WEATHER, components));
        }

        scored.sort(Comparator.comparingInt((ScoredWindow item) -> item.components.total()).reversed()
                .thenComparing(item -> item.window.peak.instant));
        if (scored.size() > config.limit) {
            scored = scored.subList(0, config.limit);
        }

        Json out = new Json();
        out.line("{");
        out.field("status", "ok", true);
        out.field("prototype", "jvm_ephemeris_scoring_fixture", true);
        out.field("ephemerisSource", "Astronomy Engine 2.1.19 via JitPack", true);
        out.field("generatedAt", Instant.now().toString(), true);
        out.line("\"location\": {");
        out.field("id", config.location.id, true);
        out.field("kind", config.location.kind, true);
        out.field("displayName", config.location.displayName, true);
        out.field("latitude", config.location.latitude, 5, true);
        out.field("longitude", config.location.longitude, 5, true);
        out.field("elevationMeters", config.location.elevationMeters, 1, true);
        out.field("timezone", config.location.timezone, true);
        out.field("countryCode", config.location.countryCode, false);
        out.line("},");
        out.field("forecastHorizonDays", config.days, true);
        out.field("startsAt", config.start.toString(), true);
        out.field("endsAt", config.end().toString(), true);
        out.field("sampleStepMinutes", config.stepMinutes, true);
        out.field("samplesEvaluated", samples.size(), true);
        out.field("maxMoonAltitudeDegrees", config.maxMoonAltitudeDegrees, true);
        out.field("minScore", config.minScore, true);
        out.line("\"opportunities\": [");
        for (int i = 0; i < scored.size(); i++) {
            scored.get(i).writeJson(out, i < scored.size() - 1);
        }
        out.line("],");
        out.line("\"rejected\": [");
        for (int i = 0; i < rejected.size(); i++) {
            rejected.get(i).writeJson(out, i < rejected.size() - 1);
        }
        out.line("],");
        out.line("\"messages\": [");
        writeMessages(out);
        out.line("],");
        out.line("\"diagnostics\": {");
        out.field("note", "Prototype only: fixture weather, no persistence, HTTP API, database, or backend framework.", true);
        out.field("selectionRule", "Contiguous samples where the apparent refracted Moon altitude is between 0 degrees and the configured maximum.", true);
        out.field("weatherSource", "fixed_fixture", true);
        out.line("\"rejectedCounts\": {");
        writeCounts(out, rejectedCounts);
        out.line("}");
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
                .max(Comparator.comparingInt(MoonWindowPrototype::candidateFit))
                .orElseThrow();
        Duration halfStep = Duration.ofSeconds(config.stepMinutes * 30L);
        Instant startsAt = max(config.start, samples.get(0).instant.minus(halfStep));
        Instant endsAt = min(config.end(), samples.get(samples.size() - 1).instant.plus(halfStep));
        windows.add(new MoonWindow(config.location.slug, startsAt, peak, endsAt, samples.size()));
        samples.clear();
    }

    private static int candidateFit(MoonSample sample) {
        return scoreMoonAltitude(sample.moonAltitudeDegrees) + scoreSunLight(sample.sunAltitudeDegrees);
    }

    private static ComponentScores scoreWindow(MoonWindow window, WeatherFixture weather) {
        return new ComponentScores(
                scoreMoonAltitude(window.peak.moonAltitudeDegrees),
                scoreSunLight(window.peak.sunAltitudeDegrees),
                scoreIllumination(window.peak.moonIlluminationPercent),
                scoreWeather(weather),
                scoreConfidence(weather.forecastAgeHours)
        );
    }

    private static int scoreMoonAltitude(double altitude) {
        if (altitude < 0.0 || altitude > 12.0) {
            return 0;
        }
        if (altitude >= 1.0 && altitude <= 6.0) {
            return 30;
        }
        if (altitude < 1.0) {
            return Math.toIntExact(Math.round(18.0 + altitude * 6.0));
        }
        return Math.toIntExact(Math.round(30.0 - ((altitude - 6.0) / 6.0) * 12.0));
    }

    private static int scoreSunLight(double sunAltitude) {
        return switch (lightBucket(sunAltitude)) {
            case "golden_hour" -> 25;
            case "civil_twilight" -> 24;
            case "daylight" -> 16;
            case "nautical_twilight" -> 14;
            default -> 7;
        };
    }

    private static int scoreIllumination(double percent) {
        if (percent >= 95.0) {
            return 15;
        }
        if (percent >= 85.0) {
            return 12;
        }
        if (percent >= 70.0) {
            return 10;
        }
        if (percent >= 30.0) {
            return 8;
        }
        if (percent >= 5.0) {
            return 6;
        }
        return 4;
    }

    private static int scoreWeather(WeatherFixture weather) {
        int cloudScore = Math.max(0, 13 - Math.toIntExact(Math.round(Math.abs(weather.cloudCoverPercent - 35) / 5.0)));
        int precipScore = Math.max(0, 7 - Math.toIntExact(Math.round(weather.precipitationProbabilityPercent / 5.0)));
        int visibilityScore = weather.visibilityMeters >= 20000 ? 5 : weather.visibilityMeters >= 15000 ? 4 : 2;
        return Math.min(25, cloudScore + precipScore + visibilityScore);
    }

    private static int scoreConfidence(double forecastAgeHours) {
        if (forecastAgeHours <= 3.0) {
            return 5;
        }
        if (forecastAgeHours <= 12.0) {
            return 4;
        }
        if (forecastAgeHours <= 24.0) {
            return 3;
        }
        return 2;
    }

    private static String confidenceLabel(int score) {
        if (score >= 85) {
            return "high";
        }
        if (score >= 65) {
            return "medium";
        }
        return "low";
    }

    private static String weatherSummary(WeatherFixture weather) {
        if (weather.weatherCode == 0 || weather.weatherCode == 1) {
            return "clear to mostly clear";
        }
        if (weather.weatherCode == 2 || weather.weatherCode == 3) {
            return "partly cloudy";
        }
        if (weather.weatherCode >= 50) {
            return "rain likely";
        }
        return "mixed conditions";
    }

    private static void countRejections(Map<String, Integer> rejectedCounts, List<String> reasons) {
        for (String reason : reasons) {
            rejectedCounts.put(reason, rejectedCounts.getOrDefault(reason, 0) + 1);
        }
    }

    private static void writeCounts(Json out, Map<String, Integer> counts) {
        int index = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.field(entry.getKey(), entry.getValue(), index < counts.size() - 1);
            index++;
        }
    }

    private static void writeMessages(Json out) {
        out.line("{");
        out.field("level", "info", true);
        out.field("code", "local_horizon_not_modelled", true);
        out.field("text", "Local hills, buildings, or trees may affect exact visibility near the horizon.", false);
        out.line("},");
        out.line("{");
        out.field("level", "info", true);
        out.field("code", "fixture_weather", true);
        out.field("text", "This JVM prototype uses fixed weather while exercising real Astronomy Engine Moon and Sun samples.", false);
        out.line("}");
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
                "  --step-minutes N            Sampling step. Default: 5.",
                "  --max-altitude DEG          Low-Moon ceiling. Default: 12.",
                "  --min-score N               Minimum returned score. Default: 50.",
                "  --limit N                   Maximum returned windows. Default: 10."
        );
    }

    private record Location(
            String slug,
            String kind,
            String id,
            String displayName,
            double latitude,
            double longitude,
            double elevationMeters,
            String timezone,
            String countryCode
    ) {
    }

    private record Config(
            Location location,
            Instant start,
            int days,
            int stepMinutes,
            double maxMoonAltitudeDegrees,
            int minScore,
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
            int minScore = DEFAULT_MIN_SCORE;
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
                    case "--min-score" -> minScore = parseInt(arg, value, 0, 100);
                    case "--limit" -> limit = parseInt(arg, value, 1, 100);
                    default -> throw new UsageException("Unknown option: " + arg);
                }
            }

            if (!locationId.equals(PRAGUE.slug)) {
                throw new UsageException("Unsupported location for this prototype: " + locationId);
            }
            return new Config(PRAGUE, start, days, stepMinutes, maxMoonAltitudeDegrees, minScore, limit);
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
    }

    private record MoonWindow(
            String locationSlug,
            Instant startsAt,
            MoonSample peak,
            Instant endsAt,
            int sampleCount
    ) {
        String id() {
            return formatOpportunityId(locationSlug, peak.instant);
        }
    }

    private static String formatOpportunityId(String locationSlug, Instant peak) {
        return locationSlug + "-" + peak.toString()
                .replace(":00Z", "Z")
                .replace(":", "");
    }

    private record WeatherFixture(
            int cloudCoverPercent,
            int lowCloudCoverPercent,
            int midCloudCoverPercent,
            int highCloudCoverPercent,
            int precipitationProbabilityPercent,
            double precipitationMm,
            int visibilityMeters,
            int weatherCode,
            double forecastAgeHours
    ) {
    }

    private record ComponentScores(
            int moonAltitudeFit,
            int sunLightFit,
            int moonIlluminationFit,
            int weatherFit,
            int forecastConfidence
    ) {
        int total() {
            return moonAltitudeFit + sunLightFit + moonIlluminationFit + weatherFit + forecastConfidence;
        }
    }

    private record RejectedWindow(
            String id,
            Integer score,
            List<String> reasons
    ) {
        void writeJson(Json out, boolean comma) {
            out.line("{");
            out.field("id", id, score != null || !reasons.isEmpty());
            if (score != null) {
                out.field("score", score, !reasons.isEmpty());
            }
            if (!reasons.isEmpty()) {
                out.line("\"reasons\": [");
                for (int i = 0; i < reasons.size(); i++) {
                    out.stringValue(reasons.get(i), i < reasons.size() - 1);
                }
                out.line("]");
            }
            out.line(comma ? "}," : "}");
        }
    }

    private record ScoredWindow(
            MoonWindow window,
            WeatherFixture weather,
            ComponentScores components
    ) {
        void writeJson(Json out, boolean comma) {
            String id = window.id();
            out.line("{");
            out.field("id", id, true);
            out.field("startsAt", window.startsAt.toString(), true);
            out.field("peaksAt", window.peak.instant.toString(), true);
            out.field("endsAt", window.endsAt.toString(), true);
            out.field("localTimeZone", PRAGUE.timezone, true);
            out.field("score", components.total(), true);
            out.field("confidence", confidenceLabel(components.total()), true);
            out.line("\"components\": {");
            out.field("moonAltitudeFit", components.moonAltitudeFit, true);
            out.field("sunLightFit", components.sunLightFit, true);
            out.field("moonIlluminationFit", components.moonIlluminationFit, true);
            out.field("weatherFit", components.weatherFit, true);
            out.field("forecastConfidence", components.forecastConfidence, false);
            out.line("},");
            out.field("sampleCount", window.sampleCount, true);
            out.line("\"moon\": {");
            out.field("altitudeDegrees", round3(window.peak.moonAltitudeDegrees), true);
            out.field("azimuthDegrees", round3(window.peak.moonAzimuthDegrees), true);
            out.field("illuminationPercent", round3(window.peak.moonIlluminationPercent), false);
            out.line("},");
            out.line("\"sun\": {");
            out.field("altitudeDegrees", round3(window.peak.sunAltitudeDegrees), true);
            out.field("lightBucket", lightBucket(window.peak.sunAltitudeDegrees), false);
            out.line("},");
            out.line("\"weather\": {");
            out.field("cloudCoverPercent", weather.cloudCoverPercent, true);
            out.field("lowCloudCoverPercent", weather.lowCloudCoverPercent, true);
            out.field("midCloudCoverPercent", weather.midCloudCoverPercent, true);
            out.field("highCloudCoverPercent", weather.highCloudCoverPercent, true);
            out.field("precipitationProbabilityPercent", weather.precipitationProbabilityPercent, true);
            out.field("precipitationMm", weather.precipitationMm, true);
            out.field("visibilityMeters", weather.visibilityMeters, true);
            out.field("weatherCode", weather.weatherCode, true);
            out.field("summary", weatherSummary(weather), false);
            out.line("},");
            out.line("\"exposureBalance\": {");
            out.field("label", exposureBalance(window.peak), true);
            out.field("text", exposureText(window.peak), false);
            out.line("},");
            out.field("reason", reasonText(), true);
            out.line("\"links\": {");
            out.field("ics", "/o/" + id + ".ics", false);
            out.line("}");
            out.line(comma ? "}," : "}");
        }

        private String reasonText() {
            return String.format(
                    Locale.ROOT,
                    "Moon is %.1f degrees above the horizon at azimuth %.0f degrees, %.1f percent illuminated, during %s with %s and %d percent precipitation risk. %s",
                    window.peak.moonAltitudeDegrees,
                    window.peak.moonAzimuthDegrees,
                    window.peak.moonIlluminationPercent,
                    lightBucket(window.peak.sunAltitudeDegrees).replace("_", " "),
                    weatherSummary(weather),
                    weather.precipitationProbabilityPercent,
                    exposureText(window.peak)
            );
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

        void stringValue(String value, boolean comma) {
            line("\"" + escape(value) + "\"" + (comma ? "," : ""));
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
