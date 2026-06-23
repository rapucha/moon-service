package dev.moonservice.backend.weather;

public final class WeatherForecastUnavailableException extends RuntimeException {
    public WeatherForecastUnavailableException(String message) {
        super(message);
    }

    public WeatherForecastUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
