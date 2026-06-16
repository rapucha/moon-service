package dev.moonservice.scoringprototype.input;

import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.UsageException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public record PrototypeConfig(
        Location location,
        LocalDate startDate,
        int days,
        double maxMoonAltitudeDegrees,
        int limit
) {
    static final int DEFAULT_DAYS = 7;
    static final double DEFAULT_MAX_MOON_ALTITUDE = 12.0;
    static final LocalDate DEFAULT_START_DATE = LocalDate.parse("2026-06-29");

    public Instant start() {
        return startDate.atStartOfDay(location.zoneId()).toInstant();
    }

    public Instant end() {
        return startDate.plusDays(days).atStartOfDay(location.zoneId()).toInstant();
    }

    public static PrototypeConfig defaults() {
        return new PrototypeConfig(
                Locations.PRAGUE,
                DEFAULT_START_DATE,
                DEFAULT_DAYS,
                DEFAULT_MAX_MOON_ALTITUDE,
                10
        );
    }

    public static PrototypeConfig parse(String[] args) {
        String locationSlug = Locations.PRAGUE.slug();
        LocalDate startDate = DEFAULT_START_DATE;
        int days = DEFAULT_DAYS;
        double maxMoonAltitudeDegrees = DEFAULT_MAX_MOON_ALTITUDE;
        int limit = 10;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = requireValue(args, ++i, arg);
            switch (arg) {
                case "--location" -> locationSlug = value;
                case "--start" -> startDate = parseStartDate(value);
                case "--days" -> days = parseInt(arg, value, 1, 30);
                case "--max-altitude" -> maxMoonAltitudeDegrees = parseDouble(arg, value, 0.0, 45.0);
                case "--limit" -> limit = parseInt(arg, value, 1, 100);
                default -> throw new UsageException("Unknown option: " + arg);
            }
        }

        return new PrototypeConfig(
                Locations.requireFixture(locationSlug),
                startDate,
                days,
                maxMoonAltitudeDegrees,
                limit
        );
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new UsageException("Missing value for " + option);
        }
        return args[index];
    }

    static LocalDate parseStartDate(String value) {
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value);
            }
            return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate();
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
