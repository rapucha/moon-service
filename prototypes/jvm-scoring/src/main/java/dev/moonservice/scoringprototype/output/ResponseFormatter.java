package dev.moonservice.scoringprototype.output;

import dev.moonservice.scoringprototype.Json;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ComponentScores;
import dev.moonservice.scoringprototype.scoring.ScoredWindow;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.service.PrototypeResult;
import dev.moonservice.scoringprototype.window.MoonWindow;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class ResponseFormatter {
    public String format(PrototypeResult result) {
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
        out.field("candidateWindowsEvaluated", result.candidateWindowsEvaluated(), true);
        out.field("maxMoonAltitudeDegrees", config.maxMoonAltitudeDegrees(), true);
        writeOpportunities(out, result.opportunities());
        writeRejected(out);
        writeMessages(out);
        writeDiagnostics(out);
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
        out.field("windowKind", window.kind(), true);
        out.field("startsAt", window.startsAt().toString(), true);
        out.field("suggestedAt", window.suggested().instant().toString(), true);
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
        out.line("\"moon\": {");
        out.field("altitudeDegrees", round3(window.suggested().moonAltitudeDegrees()), true);
        out.field("azimuthDegrees", round3(window.suggested().moonAzimuthDegrees()), true);
        out.field("illuminationPercent", round3(window.suggested().moonIlluminationPercent()), false);
        out.line("},");
        out.line("\"sun\": {");
        out.field("altitudeDegrees", round3(window.suggested().sunAltitudeDegrees()), true);
        out.field("lightBucket", ScoringModel.lightBucket(window.suggested().sunAltitudeDegrees()), false);
        out.line("},");
        out.line("\"weather\": {");
        out.field("sourceResolution", "hourly", true);
        out.field("segmentKind", weatherSegmentKind(weather), true);
        out.field("cloudCoverMeanPercent", weather.cloudCoverPercent(), true);
        out.field("cloudCoverMaxPercent", weather.cloudCoverPercent(), true);
        out.field("lowCloudCoverMaxPercent", weather.lowCloudCoverPercent(), true);
        out.field("midCloudCoverMaxPercent", weather.midCloudCoverPercent(), true);
        out.field("highCloudCoverMaxPercent", weather.highCloudCoverPercent(), true);
        out.field("precipitationProbabilityMaxPercent", weather.precipitationProbabilityPercent(), true);
        out.field("precipitationMm", weather.precipitationMm(), true);
        out.field("visibilityMinMeters", weather.visibilityMeters(), true);
        out.field("weatherCode", weather.weatherCode(), true);
        out.field("summary", ScoringModel.weatherSummary(weather), false);
        out.line("},");
        out.line("\"exposureBalance\": {");
        out.field("label", ScoringModel.exposureBalance(window.suggested()), true);
        out.field("text", ScoringModel.exposureText(window.suggested()), false);
        out.line("},");
        out.field("reason", reasonText(window.suggested(), weather), true);
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

    private static String weatherSegmentKind(WeatherFixture weather) {
        if (weather.weatherCode() == 0 || weather.weatherCode() == 1) {
            return "clear";
        }
        if (weather.weatherCode() == 2 || weather.weatherCode() == 3) {
            return "partly_cloudy";
        }
        if (weather.weatherCode() >= 50) {
            return "precipitation_risk";
        }
        return "mixed";
    }

    private static void writeRejected(Json out) {
        out.line("\"rejected\": [],");
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

    private static void writeDiagnostics(Json out) {
        out.line("\"diagnostics\": {");
        out.field("note", "Prototype only: fixture weather, no persistence, HTTP API, database, or backend framework.", true);
        out.field("selectionRule", "Natural low-Moon windows bounded by Moon altitude crossings and local day boundaries.", true);
        out.field("weatherSource", "fixed_fixture", true);
        out.field("weatherResolution", "hourly_fixture", false);
        out.line("}");
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
