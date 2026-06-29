package dev.moonservice.backend.weather.openmeteo;

import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.weather.HourlyWeather;
import dev.moonservice.backend.weather.HourlyWeatherForecast;
import dev.moonservice.backend.weather.WeatherForecast;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;
import dev.moonservice.backend.openmeteo.OpenMeteoTransport;
import dev.moonservice.backend.openmeteo.OpenMeteoTransportException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OpenMeteoWeatherClient implements WeatherForecastProvider {
    private static final double FORECAST_AGE_HOURS = 1.0;
    private static final String HOURLY_VARIABLES = String.join(
            ",",
            "cloud_cover",
            "cloud_cover_low",
            "cloud_cover_mid",
            "cloud_cover_high",
            "precipitation_probability",
            "precipitation",
            "weather_code",
            "visibility");
    private static final DateTimeFormatter OPEN_METEO_HOUR =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC);

    private final URI endpoint;
    private final OpenMeteoTransport transport;
    private final ObjectMapper objectMapper;

    public OpenMeteoWeatherClient(
            URI endpoint,
            OpenMeteoTransport transport
    ) {
        this(endpoint, transport, new ObjectMapper());
    }

    OpenMeteoWeatherClient(
            URI endpoint,
            OpenMeteoTransport transport,
            ObjectMapper objectMapper
    ) {
        this.endpoint = endpoint;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    @Override
    public WeatherForecast forecastFor(
            ResolvedLocation location,
            Instant startsAt,
            Instant endsAt,
            int forecastHorizonDays
    ) {
        String body;
        try {
            body = transport.get(requestUri(location, startsAt, endsAt));
        } catch (OpenMeteoTransportException ex) {
            throw new WeatherForecastUnavailableException("Weather lookup is temporarily unavailable.", ex);
        }

        if (body == null || body.isBlank()) {
            throw new WeatherForecastUnavailableException("Weather provider returned an empty response.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException ex) {
            throw new WeatherForecastUnavailableException("Weather provider response was not valid JSON.", ex);
        }

        return toForecast(root);
    }

    URI requestUri(ResolvedLocation location, Instant startsAt, Instant endsAt) {
        Instant startHour = startsAt.truncatedTo(ChronoUnit.HOURS);
        Instant endHour = endsAt.minusSeconds(1).truncatedTo(ChronoUnit.HOURS);
        if (endHour.isBefore(startHour)) {
            throw new IllegalArgumentException("Weather forecast end must be after start.");
        }

        return UriComponentsBuilder.fromUri(endpoint)
                .queryParam("latitude", coordinate(location.latitude()))
                .queryParam("longitude", coordinate(location.longitude()))
                .queryParam("elevation", location.elevationMeters())
                .queryParam("hourly", HOURLY_VARIABLES)
                .queryParam("timezone", "UTC")
                .queryParam("timeformat", "unixtime")
                .queryParam("start_hour", OPEN_METEO_HOUR.format(startHour))
                .queryParam("end_hour", OPEN_METEO_HOUR.format(endHour))
                .build()
                .encode()
                .toUri();
    }

    private static WeatherForecast toForecast(JsonNode root) {
        JsonNode hourly = root.path("hourly");
        JsonNode times = hourly.path("time");
        if (!times.isArray()) {
            throw new WeatherForecastUnavailableException("Weather provider response did not include hourly time data.");
        }

        int size = times.size();
        if (size == 0) {
            throw new WeatherForecastUnavailableException("Weather provider response did not include hourly records.");
        }

        List<HourlyWeather> hours = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            hours.add(new HourlyWeather(
                    instantAt(times, index),
                    percentAt(hourly, "cloud_cover", index, size),
                    percentAt(hourly, "cloud_cover_low", index, size),
                    percentAt(hourly, "cloud_cover_mid", index, size),
                    percentAt(hourly, "cloud_cover_high", index, size),
                    percentAt(hourly, "precipitation_probability", index, size),
                    nonNegativeDoubleAt(hourly, "precipitation", index, size),
                    nonNegativeIntAt(hourly, "visibility", index, size),
                    nonNegativeIntAt(hourly, "weather_code", index, size),
                    FORECAST_AGE_HOURS
            ));
        }
        return new HourlyWeatherForecast(hours);
    }

    private static Instant instantAt(JsonNode times, int index) {
        return Instant.ofEpochSecond(longValue(times.get(index), "hourly timestamp"));
    }

    private static int percentAt(JsonNode hourly, String fieldName, int index, int expectedSize) {
        int value = nonNegativeIntAt(hourly, fieldName, index, expectedSize);
        if (value > 100) {
            throw new WeatherForecastUnavailableException("Weather provider field " + fieldName + " was above 100.");
        }
        return value;
    }

    private static int nonNegativeIntAt(JsonNode hourly, String fieldName, int index, int expectedSize) {
        JsonNode values = arrayField(hourly, fieldName, expectedSize);
        int value = intValue(values.get(index), fieldName);
        if (value < 0) {
            throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".");
        }
        return value;
    }

    private static double nonNegativeDoubleAt(JsonNode hourly, String fieldName, int index, int expectedSize) {
        JsonNode values = arrayField(hourly, fieldName, expectedSize);
        double value = doubleValue(values.get(index), fieldName);
        if (value < 0.0) {
            throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".");
        }
        return value;
    }

    private static JsonNode arrayField(JsonNode hourly, String fieldName, int expectedSize) {
        JsonNode values = hourly.path(fieldName);
        if (!values.isArray() || values.size() != expectedSize) {
            throw new WeatherForecastUnavailableException(
                    "Weather provider field " + fieldName + " did not match hourly time data.");
        }
        return values;
    }

    private static long longValue(JsonNode node, String fieldName) {
        if (node == null) {
            throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".");
        }
        try {
            return Long.parseLong(node.asString());
        } catch (NumberFormatException ex) {
            throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".", ex);
        }
    }

    private static int intValue(JsonNode node, String fieldName) {
        if (node == null) {
            throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".");
        }
        try {
            double value = Double.parseDouble(node.asString());
            if (!Double.isFinite(value)) {
                throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".");
            }
            return Math.toIntExact(Math.round(value));
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".", ex);
        }
    }

    private static double doubleValue(JsonNode node, String fieldName) {
        if (node == null) {
            throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".");
        }
        try {
            double value = Double.parseDouble(node.asString());
            if (!Double.isFinite(value)) {
                throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new WeatherForecastUnavailableException("Weather provider returned invalid " + fieldName + ".", ex);
        }
    }

    private static String coordinate(double value) {
        double rounded = Math.round(value * 10000.0) / 10000.0;
        return String.format(Locale.ROOT, "%.4f", rounded);
    }
}
