package dev.moonservice.prototype;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ResponseFormatter {
    String format(PrototypeResult result) {
        PrototypeConfig config = result.config();
        Json out = new Json();
        out.line("{");
        out.field("status", "ok", true);
        out.field("prototype", "jvm_maven_ephemeris_scoring_fixture", true);
        out.field("ephemerisSource", "Astronomy Engine 2.1.19 via JitPack", true);
        out.field("generatedAt", Instant.now().toString(), true);
        writeLocation(out, config.location());
        out.field("forecastHorizonDays", config.days(), true);
        out.field("startsAt", config.start().toString(), true);
        out.field("endsAt", config.end().toString(), true);
        out.field("sampleStepMinutes", config.stepMinutes(), true);
        out.field("samplesEvaluated", result.samples().size(), true);
        out.field("maxMoonAltitudeDegrees", config.maxMoonAltitudeDegrees(), true);
        out.field("minScore", config.minScore(), true);
        writeOpportunities(out, result.opportunities());
        writeRejected(out, result.rejected());
        writeMessages(out);
        writeDiagnostics(out, result.rejectedCounts());
        out.line("}");
        return out.toString();
    }

    private static void writeLocation(Json out, Location location) {
        out.line("\"location\": {");
        out.field("id", location.id(), true);
        out.field("kind", location.kind(), true);
        out.field("displayName", location.displayName(), true);
        out.field("latitude", location.latitude(), 5, true);
        out.field("longitude", location.longitude(), 5, true);
        out.field("elevationMeters", location.elevationMeters(), 1, true);
        out.field("timezone", location.timezone(), true);
        out.field("countryCode", location.countryCode(), false);
        out.line("},");
    }

    private static void writeOpportunities(Json out, List<ScoredWindow> scored) {
        out.line("\"opportunities\": [");
        for (int i = 0; i < scored.size(); i++) {
            writeOpportunity(out, scored.get(i), i < scored.size() - 1);
        }
        out.line("],");
    }

    private static void writeOpportunity(Json out, ScoredWindow scored, boolean comma) {
        MoonWindow window = scored.window();
        WeatherFixture weather = scored.weather();
        ComponentScores components = scored.components();
        String id = window.id();
        out.line("{");
        out.field("id", id, true);
        out.field("startsAt", window.startsAt().toString(), true);
        out.field("peaksAt", window.peak().instant().toString(), true);
        out.field("endsAt", window.endsAt().toString(), true);
        out.field("localTimeZone", window.location().timezone(), true);
        out.field("score", components.total(), true);
        out.field("confidence", ScoringModel.confidenceLabel(components.total()), true);
        out.line("\"components\": {");
        out.field("moonAltitudeFit", components.moonAltitudeFit(), true);
        out.field("sunLightFit", components.sunLightFit(), true);
        out.field("moonIlluminationFit", components.moonIlluminationFit(), true);
        out.field("weatherFit", components.weatherFit(), true);
        out.field("forecastConfidence", components.forecastConfidence(), false);
        out.line("},");
        out.field("sampleCount", window.sampleCount(), true);
        out.line("\"moon\": {");
        out.field("altitudeDegrees", round3(window.peak().moonAltitudeDegrees()), true);
        out.field("azimuthDegrees", round3(window.peak().moonAzimuthDegrees()), true);
        out.field("illuminationPercent", round3(window.peak().moonIlluminationPercent()), false);
        out.line("},");
        out.line("\"sun\": {");
        out.field("altitudeDegrees", round3(window.peak().sunAltitudeDegrees()), true);
        out.field("lightBucket", ScoringModel.lightBucket(window.peak().sunAltitudeDegrees()), false);
        out.line("},");
        out.line("\"weather\": {");
        out.field("cloudCoverPercent", weather.cloudCoverPercent(), true);
        out.field("lowCloudCoverPercent", weather.lowCloudCoverPercent(), true);
        out.field("midCloudCoverPercent", weather.midCloudCoverPercent(), true);
        out.field("highCloudCoverPercent", weather.highCloudCoverPercent(), true);
        out.field("precipitationProbabilityPercent", weather.precipitationProbabilityPercent(), true);
        out.field("precipitationMm", weather.precipitationMm(), true);
        out.field("visibilityMeters", weather.visibilityMeters(), true);
        out.field("weatherCode", weather.weatherCode(), true);
        out.field("summary", ScoringModel.weatherSummary(weather), false);
        out.line("},");
        out.line("\"exposureBalance\": {");
        out.field("label", ScoringModel.exposureBalance(window.peak()), true);
        out.field("text", ScoringModel.exposureText(window.peak()), false);
        out.line("},");
        out.field("reason", reasonText(window.peak(), weather), true);
        out.line("\"links\": {");
        out.field("ics", "/o/" + id + ".ics", false);
        out.line("}");
        out.line(comma ? "}," : "}");
    }

    private static String reasonText(MoonSample sample, WeatherFixture weather) {
        return String.format(
                Locale.ROOT,
                "Moon is %.1f degrees above the horizon at azimuth %.0f degrees, %.1f percent illuminated, during %s with %s and %d percent precipitation risk. %s",
                sample.moonAltitudeDegrees(),
                sample.moonAzimuthDegrees(),
                sample.moonIlluminationPercent(),
                ScoringModel.lightBucket(sample.sunAltitudeDegrees()).replace("_", " "),
                ScoringModel.weatherSummary(weather),
                weather.precipitationProbabilityPercent(),
                ScoringModel.exposureText(sample)
        );
    }

    private static void writeRejected(Json out, List<RejectedWindow> rejected) {
        out.line("\"rejected\": [");
        for (int i = 0; i < rejected.size(); i++) {
            writeRejectedWindow(out, rejected.get(i), i < rejected.size() - 1);
        }
        out.line("],");
    }

    private static void writeRejectedWindow(Json out, RejectedWindow rejected, boolean comma) {
        out.line("{");
        out.field("id", rejected.id(), rejected.score() != null || !rejected.reasons().isEmpty());
        if (rejected.score() != null) {
            out.field("score", rejected.score(), !rejected.reasons().isEmpty());
        }
        if (!rejected.reasons().isEmpty()) {
            out.line("\"reasons\": [");
            for (int i = 0; i < rejected.reasons().size(); i++) {
                out.stringValue(rejected.reasons().get(i), i < rejected.reasons().size() - 1);
            }
            out.line("]");
        }
        out.line(comma ? "}," : "}");
    }

    private static void writeMessages(Json out) {
        out.line("\"messages\": [");
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
        out.line("],");
    }

    private static void writeDiagnostics(Json out, Map<String, Integer> rejectedCounts) {
        out.line("\"diagnostics\": {");
        out.field("note", "Prototype only: fixture weather, no persistence, HTTP API, database, or backend framework.", true);
        out.field("selectionRule", "Contiguous samples where the apparent refracted Moon altitude is between 0 degrees and the configured maximum.", true);
        out.field("weatherSource", "fixed_fixture", true);
        out.line("\"rejectedCounts\": {");
        writeCounts(out, rejectedCounts);
        out.line("}");
        out.line("}");
    }

    private static void writeCounts(Json out, Map<String, Integer> counts) {
        int index = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.field(entry.getKey(), entry.getValue(), index < counts.size() - 1);
            index++;
        }
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
