package dev.moonservice.scoringprototype;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

record PrototypeConfig(
        Location location,
        Instant start,
        int days,
        int stepMinutes,
        double maxMoonAltitudeDegrees,
        int minScore,
        int limit
) {
    static final int DEFAULT_DAYS = 7;
    static final int DEFAULT_STEP_MINUTES = 30;
    static final int DEFAULT_MIN_SCORE = 50;
    static final double DEFAULT_MAX_MOON_ALTITUDE = 12.0;

    Instant end() {
        return start.plus(Duration.ofDays(days));
    }

    static PrototypeConfig defaults() {
        return new PrototypeConfig(
                Locations.PRAGUE,
                LocalDate.parse("2026-06-29").atStartOfDay().toInstant(ZoneOffset.UTC),
                DEFAULT_DAYS,
                DEFAULT_STEP_MINUTES,
                DEFAULT_MAX_MOON_ALTITUDE,
                DEFAULT_MIN_SCORE,
                10
        );
    }

    static PrototypeConfig parse(String[] args) {
        String locationSlug = Locations.PRAGUE.slug();
        Instant start = defaults().start();
        int days = DEFAULT_DAYS;
        int stepMinutes = DEFAULT_STEP_MINUTES;
        double maxMoonAltitudeDegrees = DEFAULT_MAX_MOON_ALTITUDE;
        int minScore = DEFAULT_MIN_SCORE;
        int limit = 10;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = requireValue(args, ++i, arg);
            switch (arg) {
                case "--location" -> locationSlug = value;
                case "--start" -> start = parseStart(value);
                case "--days" -> days = parseInt(arg, value, 1, 30);
                case "--step-minutes" -> stepMinutes = parseInt(arg, value, 1, 180);
                case "--max-altitude" -> maxMoonAltitudeDegrees = parseDouble(arg, value, 0.0, 45.0);
                case "--min-score" -> minScore = parseInt(arg, value, 0, 100);
                case "--limit" -> limit = parseInt(arg, value, 1, 100);
                default -> throw new UsageException("Unknown option: " + arg);
            }
        }

        return new PrototypeConfig(
                Locations.requireFixture(locationSlug),
                start,
                days,
                stepMinutes,
                maxMoonAltitudeDegrees,
                minScore,
                limit
        );
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new UsageException("Missing value for " + option);
        }
        return args[index];
    }

    static Instant parseStart(String value) {
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
