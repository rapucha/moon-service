package dev.moonservice.prototype;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PreviewResponseFactory {
    PreviewResponse from(PrototypeResult result) {
        PrototypeConfig config = result.config();
        return new PreviewResponse(
                "ok",
                "jvm_maven_ephemeris_scoring_fixture",
                "Astronomy Engine 2.1.19 via JitPack",
                Instant.now().toString(),
                location(config.location()),
                config.days(),
                config.start().toString(),
                config.end().toString(),
                config.stepMinutes(),
                result.samples().size(),
                config.maxMoonAltitudeDegrees(),
                config.minScore(),
                result.opportunities().stream().map(this::opportunity).toList(),
                result.rejected().stream().map(PreviewResponseFactory::rejected).toList(),
                messages(),
                diagnostics(result.rejectedCounts())
        );
    }

    private LocationResponse location(Location location) {
        return new LocationResponse(
                location.id(),
                location.kind(),
                location.displayName(),
                round5(location.latitude()),
                round5(location.longitude()),
                round1(location.elevationMeters()),
                location.timezone(),
                location.countryCode()
        );
    }

    private OpportunityResponse opportunity(ScoredWindow scored) {
        MoonWindow window = scored.window();
        MoonSample peak = window.peak();
        WeatherFixture weather = scored.weather();
        ComponentScores components = scored.components();
        String id = window.id();
        return new OpportunityResponse(
                id,
                window.startsAt().toString(),
                peak.instant().toString(),
                window.endsAt().toString(),
                window.location().timezone(),
                components.total(),
                ScoringModel.confidenceLabel(components.total()),
                components(components),
                window.sampleCount(),
                moon(peak),
                sun(peak),
                weather(weather),
                exposureBalance(peak),
                reasonText(peak, weather),
                new LinksResponse("/o/" + id + ".ics")
        );
    }

    private static ComponentsResponse components(ComponentScores components) {
        return new ComponentsResponse(
                components.moonAltitudeFit(),
                components.sunLightFit(),
                components.moonIlluminationFit(),
                components.weatherFit(),
                components.forecastConfidence()
        );
    }

    private static MoonResponse moon(MoonSample sample) {
        return new MoonResponse(
                round3(sample.moonAltitudeDegrees()),
                round3(sample.moonAzimuthDegrees()),
                round3(sample.moonIlluminationPercent())
        );
    }

    private static SunResponse sun(MoonSample sample) {
        return new SunResponse(
                round3(sample.sunAltitudeDegrees()),
                ScoringModel.lightBucket(sample.sunAltitudeDegrees())
        );
    }

    private static WeatherResponse weather(WeatherFixture weather) {
        return new WeatherResponse(
                weather.cloudCoverPercent(),
                weather.lowCloudCoverPercent(),
                weather.midCloudCoverPercent(),
                weather.highCloudCoverPercent(),
                weather.precipitationProbabilityPercent(),
                weather.precipitationMm(),
                weather.visibilityMeters(),
                weather.weatherCode(),
                ScoringModel.weatherSummary(weather)
        );
    }

    private static ExposureBalanceResponse exposureBalance(MoonSample sample) {
        return new ExposureBalanceResponse(
                ScoringModel.exposureBalance(sample),
                ScoringModel.exposureText(sample)
        );
    }

    private static RejectedResponse rejected(RejectedWindow rejected) {
        return new RejectedResponse(rejected.id(), rejected.score(), rejected.reasons());
    }

    private static List<MessageResponse> messages() {
        return List.of(
                new MessageResponse(
                        "info",
                        "local_horizon_not_modelled",
                        "Local hills, buildings, or trees may affect exact visibility near the horizon."
                ),
                new MessageResponse(
                        "info",
                        "fixture_weather",
                        "This JVM prototype uses fixed weather while exercising real Astronomy Engine Moon and Sun samples."
                )
        );
    }

    private static DiagnosticsResponse diagnostics(Map<String, Integer> rejectedCounts) {
        return new DiagnosticsResponse(
                "Prototype only: fixture weather, no persistence, HTTP API, database, or backend framework.",
                "Contiguous samples where the apparent refracted Moon altitude is between 0 degrees and the configured maximum.",
                "fixed_fixture",
                rejectedCounts
        );
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

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double round5(double value) {
        return Math.round(value * 100000.0) / 100000.0;
    }
}

record PreviewResponse(
        String status,
        String prototype,
        String ephemerisSource,
        String generatedAt,
        LocationResponse location,
        int forecastHorizonDays,
        String startsAt,
        String endsAt,
        int sampleStepMinutes,
        int samplesEvaluated,
        double maxMoonAltitudeDegrees,
        int minScore,
        List<OpportunityResponse> opportunities,
        List<RejectedResponse> rejected,
        List<MessageResponse> messages,
        DiagnosticsResponse diagnostics
) {
}

record LocationResponse(
        String id,
        String kind,
        String displayName,
        double latitude,
        double longitude,
        double elevationMeters,
        String timezone,
        String countryCode
) {
}

record OpportunityResponse(
        String id,
        String startsAt,
        String peaksAt,
        String endsAt,
        String localTimeZone,
        int score,
        String confidence,
        ComponentsResponse components,
        int sampleCount,
        MoonResponse moon,
        SunResponse sun,
        WeatherResponse weather,
        ExposureBalanceResponse exposureBalance,
        String reason,
        LinksResponse links
) {
}

record ComponentsResponse(
        int moonAltitudeFit,
        int sunLightFit,
        int moonIlluminationFit,
        int weatherFit,
        int forecastConfidence
) {
}

record MoonResponse(
        double altitudeDegrees,
        double azimuthDegrees,
        double illuminationPercent
) {
}

record SunResponse(
        double altitudeDegrees,
        String lightBucket
) {
}

record WeatherResponse(
        int cloudCoverPercent,
        int lowCloudCoverPercent,
        int midCloudCoverPercent,
        int highCloudCoverPercent,
        int precipitationProbabilityPercent,
        double precipitationMm,
        int visibilityMeters,
        int weatherCode,
        String summary
) {
}

record ExposureBalanceResponse(
        String label,
        String text
) {
}

record LinksResponse(String ics) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record RejectedResponse(
        String id,
        Integer score,
        List<String> reasons
) {
}

record MessageResponse(
        String level,
        String code,
        String text
) {
}

record DiagnosticsResponse(
        String note,
        String selectionRule,
        String weatherSource,
        Map<String, Integer> rejectedCounts
) {
}
