package dev.moonservice.scoringprototype.input;

import dev.moonservice.scoringprototype.fixture.Location;
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
    static final int MIN_FORECAST_HORIZON_DAYS = 1;
    static final int MAX_FORECAST_HORIZON_DAYS = 30;
    static final double MIN_MAX_MOON_ALTITUDE_DEGREES = 0.0;
    static final double MAX_MAX_MOON_ALTITUDE_DEGREES = 45.0;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 100;

    public PrototypeConfig {
        if (location == null) {
            throw new UsageException("location is required in the prototype config.");
        }
        if (startDate == null) {
            throw new UsageException("start is required in the prototype config.");
        }
        validateRange("forecastHorizonDays", days, MIN_FORECAST_HORIZON_DAYS, MAX_FORECAST_HORIZON_DAYS);
        validateRange(
                "maxMoonAltitudeDegrees",
                maxMoonAltitudeDegrees,
                MIN_MAX_MOON_ALTITUDE_DEGREES,
                MAX_MAX_MOON_ALTITUDE_DEGREES
        );
        validateRange("limit", limit, MIN_LIMIT, MAX_LIMIT);
    }

    public Instant start() {
        return startDate.atStartOfDay(location.zoneId()).toInstant();
    }

    public Instant end() {
        return startDate.plusDays(days).atStartOfDay(location.zoneId()).toInstant();
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

    private static void validateRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new UsageException(field + " must be between " + min + " and " + max + ".");
        }
    }

    private static void validateRange(String field, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new UsageException(field + " must be between " + min + " and " + max + ".");
        }
    }
}
