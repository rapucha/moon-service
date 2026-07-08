package dev.moonservice.scoringprototype.output;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String format(PrototypeResult result) {
        PrototypeConfig config = result.config();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "ok");
        root.put("prototype", "jvm_maven_ephemeris_scoring_fixture");
        root.put("ephemerisSource", "Astronomy Engine 2.1.19 via JitPack");
        root.put("generatedAt", Instant.now().toString());
        writeLocation(root, config.location());
        root.put("forecastHorizonDays", config.days());
        root.put("startsAt", config.start().toString());
        root.put("endsAt", config.end().toString());
        root.put("candidateWindowsEvaluated", result.candidateWindowsEvaluated());
        root.put("maxMoonAltitudeDegrees", config.maxMoonAltitudeDegrees());
        writeOpportunities(root, result.opportunities());
        writeRejected(root);
        writeMessages(root);
        writeDiagnostics(root);
        return root.toPrettyString();
    }

    private static void writeLocation(ObjectNode parent, Location location) {
        var locationNode = parent.putObject("location");
        locationNode.put("id", location.id());
        locationNode.put("kind", location.kind());
        locationNode.put("displayName", location.displayName());
        locationNode.put("latitude", location.latitude());
        locationNode.put("longitude", location.longitude());
        locationNode.put("elevationMeters", location.elevationMeters());
        locationNode.put("timezone", location.timezone());
        locationNode.put("countryCode", location.countryCode());
    }

    private static void writeOpportunities(ObjectNode parent, List<ScoredWindow> scored) {
        ArrayNode opportunities = parent.putArray("opportunities");
        for (ScoredWindow scoredWindow : scored) {
            writeOpportunity(opportunities.addObject(), scoredWindow);
        }
    }

    private static void writeOpportunity(ObjectNode opportunity, ScoredWindow scored) {
        MoonWindow window = scored.window();
        WeatherFixture weather = scored.weather();
        ComponentScores components = scored.components();
        String id = window.id();

        opportunity.put("id", id);
        opportunity.put("windowKind", window.kind());
        writeMoonPass(opportunity, window);
        opportunity.put("startsAt", window.startsAt().toString());
        opportunity.put("suggestedAt", window.suggested().instant().toString());
        opportunity.put("endsAt", window.endsAt().toString());
        opportunity.put("localTimeZone", window.location().timezone());
        opportunity.put("score", components.total());
        opportunity.put("confidence", ScoringModel.confidenceLabel(components.total()));

        ObjectNode componentsNode = opportunity.putObject("components");
        componentsNode.put("moonAltitudeFit", components.moonAltitudeFit());
        componentsNode.put("sunLightFit", components.sunLightFit());
        componentsNode.put("moonIlluminationFit", components.moonIlluminationFit());
        componentsNode.put("weatherFit", components.weatherFit());
        componentsNode.put("forecastConfidence", components.forecastConfidence());

        ObjectNode moon = opportunity.putObject("moon");
        moon.put("altitudeDegrees", round3(window.suggested().moonAltitudeDegrees()));
        moon.put("azimuthDegrees", round3(window.suggested().moonAzimuthDegrees()));
        moon.put("illuminationPercent", round3(window.suggested().moonIlluminationPercent()));
        moon.put("phaseAngleDegrees", round3(window.suggested().moonPhaseAngleDegrees()));
        moon.put("phaseName", phaseName(window.suggested()));

        writeMoonPath(opportunity, window);

        ObjectNode sun = opportunity.putObject("sun");
        sun.put("altitudeDegrees", round3(window.suggested().sunAltitudeDegrees()));
        sun.put("azimuthDegrees", round3(window.suggested().sunAzimuthDegrees()));
        sun.put("lightBucket", ScoringModel.lightBucket(window.suggested().sunAltitudeDegrees()));

        ObjectNode weatherNode = opportunity.putObject("weather");
        weatherNode.put("sourceResolution", "hourly");
        weatherNode.put("segmentKind", ScoringModel.weatherSegmentKind(weather));
        weatherNode.put("cloudCoverMeanPercent", weather.cloudCoverPercent());
        weatherNode.put("cloudCoverMaxPercent", weather.cloudCoverPercent());
        weatherNode.put("lowCloudCoverMaxPercent", weather.lowCloudCoverPercent());
        weatherNode.put("midCloudCoverMaxPercent", weather.midCloudCoverPercent());
        weatherNode.put("highCloudCoverMaxPercent", weather.highCloudCoverPercent());
        weatherNode.put("precipitationProbabilityMaxPercent", weather.precipitationProbabilityPercent());
        weatherNode.put("precipitationMm", weather.precipitationMm());
        weatherNode.put("visibilityMinMeters", weather.visibilityMeters());
        weatherNode.put("weatherCode", weather.weatherCode());
        weatherNode.put("summary", ScoringModel.weatherSummary(weather));

        ObjectNode exposureBalance = opportunity.putObject("exposureBalance");
        exposureBalance.put("label", ScoringModel.exposureBalance(window.suggested()));
        exposureBalance.put("text", ScoringModel.exposureText(window.suggested()));

        opportunity.put("reason", reasonText(window.suggested(), weather));

        ObjectNode links = opportunity.putObject("links");
        links.put("ics", "/o/" + id + ".ics");
    }

    private static void writeMoonPass(ObjectNode opportunity, MoonWindow window) {
        ObjectNode moonPass = opportunity.putObject("moonPass");
        moonPass.put("id", window.passId());
        moonPass.put("startsAt", window.passStartsAt().toString());
        moonPass.put("endsAt", window.passEndsAt().toString());
        writeMoonPassPath(moonPass.putObject("path"), window);
    }

    private static void writeMoonPassPath(ObjectNode path, MoonWindow window) {
        List<MoonSample> samples = window.passPathSamples();
        writeMoonPathPoint(path.putObject("start"), samples.getFirst(), "start");
        writeMoonPathPoint(path.putObject("end"), samples.getLast(), "end");

        ArrayNode sampleNodes = path.putArray("samples");
        for (MoonSample sample : samples) {
            writeMoonPathPoint(sampleNodes.addObject(), sample, roleForPass(window, sample));
        }
    }

    private static void writeMoonPath(ObjectNode opportunity, MoonWindow window) {
        ObjectNode moonPath = opportunity.putObject("moonPath");
        writeMoonPathPoint(moonPath.putObject("start"), window.start(), "start");
        writeMoonPathPoint(moonPath.putObject("suggested"), window.suggested(), "suggested");
        writeMoonPathPoint(moonPath.putObject("end"), window.end(), "end");

        ArrayNode samples = moonPath.putArray("samples");
        for (MoonSample sample : window.pathSamples()) {
            writeMoonPathPoint(samples.addObject(), sample, roleFor(window, sample));
        }
    }

    private static void writeMoonPathPoint(ObjectNode node, MoonSample sample, String role) {
        node.put("at", sample.instant().toString());
        node.put("altitudeDegrees", round3(sample.moonAltitudeDegrees()));
        node.put("azimuthDegrees", round3(sample.moonAzimuthDegrees()));
        node.put("sunAltitudeDegrees", round3(sample.sunAltitudeDegrees()));
        node.put("sunAzimuthDegrees", round3(sample.sunAzimuthDegrees()));
        node.put("lightBucket", ScoringModel.lightBucket(sample.sunAltitudeDegrees()));
        node.put("role", role);
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

    private static String phaseName(MoonSample sample) {
        double angle = normalizeDegrees(sample.moonPhaseAngleDegrees());
        if (angle < 22.5 || angle >= 337.5) {
            return "new_moon";
        }
        if (angle < 67.5) {
            return "waxing_crescent";
        }
        if (angle < 112.5) {
            return "first_quarter";
        }
        if (angle < 157.5) {
            return "waxing_gibbous";
        }
        if (angle < 202.5) {
            return "full_moon";
        }
        if (angle < 247.5) {
            return "waning_gibbous";
        }
        if (angle < 292.5) {
            return "last_quarter";
        }
        return "waning_crescent";
    }

    private static String roleFor(MoonWindow window, MoonSample sample) {
        if (sample.instant().equals(window.suggested().instant())) {
            return "suggested";
        }
        if (sample.instant().equals(window.startsAt())) {
            return "start";
        }
        if (sample.instant().equals(window.endsAt())) {
            return "end";
        }
        return "path";
    }

    private static String roleForPass(MoonWindow window, MoonSample sample) {
        if (sample.instant().equals(window.passStartsAt())) {
            return "start";
        }
        if (sample.instant().equals(window.passEndsAt())) {
            return "end";
        }
        return "path";
    }

    private static double normalizeDegrees(double value) {
        double normalized = value % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static void writeRejected(ObjectNode parent) {
        parent.putArray("rejected");
    }

    private static void writeMessages(ObjectNode parent) {
        ArrayNode messages = parent.putArray("messages");

        ObjectNode horizonMessage = messages.addObject();
        horizonMessage.put("level", "info");
        horizonMessage.put("code", "local_horizon_not_modelled");
        horizonMessage.put("text", "Local hills, buildings, or trees may affect exact visibility near the horizon.");

        ObjectNode weatherMessage = messages.addObject();
        weatherMessage.put("level", "info");
        weatherMessage.put("code", "fixture_weather");
        weatherMessage.put("text", "This JVM prototype uses fixed weather while exercising real Astronomy Engine Moon and Sun samples.");
    }

    private static void writeDiagnostics(ObjectNode parent) {
        ObjectNode diagnostics = parent.putObject("diagnostics");
        diagnostics.put("note", "Prototype only: fixture weather, no persistence, HTTP API, database, or backend framework.");
        diagnostics.put("selectionRule", "Moon passes are bounded by horizon crossings; recommendation windows inside a pass may be split by peak altitude or configured altitude thresholds.");
        diagnostics.put("weatherSource", "fixed_fixture");
        diagnostics.put("weatherResolution", "hourly_fixture");
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
