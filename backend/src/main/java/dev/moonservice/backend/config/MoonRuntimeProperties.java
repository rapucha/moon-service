package dev.moonservice.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "moon")
public final class MoonRuntimeProperties {
    private static final Duration DEFAULT_OPEN_METEO_TIMEOUT = Duration.ofSeconds(3);
    private static final int DEFAULT_OPEN_METEO_MAX_TRANSPORT_RETRIES = 1;
    private static final Duration DEFAULT_OPEN_METEO_MAX_RETRY_AFTER = Duration.ofSeconds(1);
    private static final URI DEFAULT_OPEN_METEO_GEOCODING_ENDPOINT =
            URI.create("https://geocoding-api.open-meteo.com/v1/search");
    private static final URI DEFAULT_OPEN_METEO_GEOCODING_GET_ENDPOINT =
            URI.create("https://geocoding-api.open-meteo.com/v1/get");
    private static final int DEFAULT_OPEN_METEO_GEOCODING_RESULT_COUNT = 10;
    private static final String DEFAULT_OPEN_METEO_GEOCODING_LANGUAGE = "en";
    private static final URI DEFAULT_OPEN_METEO_FORECAST_ENDPOINT =
            URI.create("https://api.open-meteo.com/v1/forecast");
    private static final long DEFAULT_GEOCODING_CACHE_MAXIMUM_SIZE = 2_000;
    private static final Duration DEFAULT_GEOCODING_CACHE_RESOLVED_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_GEOCODING_CACHE_AMBIGUOUS_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_GEOCODING_CACHE_NOT_FOUND_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_GEOCODING_CACHE_TEMPORARILY_UNAVAILABLE_TTL = Duration.ofSeconds(30);
    private static final long DEFAULT_WEATHER_CACHE_MAXIMUM_SIZE = 1_000;
    private static final Duration DEFAULT_WEATHER_CACHE_AVAILABLE_TTL = Duration.ofHours(1);
    private static final Duration DEFAULT_WEATHER_CACHE_UNAVAILABLE_TTL = Duration.ofSeconds(30);

    private final Location location = new Location();
    private final Weather weather = new Weather();
    private final OpenMeteo openMeteo = new OpenMeteo();
    private final Cache cache = new Cache();

    public Location getLocation() {
        return location;
    }

    public Weather getWeather() {
        return weather;
    }

    public OpenMeteo getOpenMeteo() {
        return openMeteo;
    }

    public Cache getCache() {
        return cache;
    }

    public static final class Location {
        private String resolver = "";

        public String getResolver() {
            return resolver;
        }

        public void setResolver(String resolver) {
            this.resolver = normalize(resolver);
        }
    }

    public static final class Weather {
        private String provider = "";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = normalize(provider);
        }
    }

    public static final class OpenMeteo {
        private Duration timeout = DEFAULT_OPEN_METEO_TIMEOUT;
        private int maxTransportRetries = DEFAULT_OPEN_METEO_MAX_TRANSPORT_RETRIES;
        private Duration maxRetryAfter = DEFAULT_OPEN_METEO_MAX_RETRY_AFTER;
        private final Geocoding geocoding = new Geocoding();
        private final Forecast forecast = new Forecast();

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = requirePositive(timeout, "moon.open-meteo.timeout");
        }

        public int getMaxTransportRetries() {
            return maxTransportRetries;
        }

        public void setMaxTransportRetries(int maxTransportRetries) {
            this.maxTransportRetries = requireZeroOrPositive(
                    maxTransportRetries,
                    "moon.open-meteo.max-transport-retries");
        }

        public Duration getMaxRetryAfter() {
            return maxRetryAfter;
        }

        public void setMaxRetryAfter(Duration maxRetryAfter) {
            this.maxRetryAfter = requireZeroOrPositive(maxRetryAfter, "moon.open-meteo.max-retry-after");
        }

        public Geocoding getGeocoding() {
            return geocoding;
        }

        public Forecast getForecast() {
            return forecast;
        }
    }

    public static final class Geocoding {
        private URI endpoint = DEFAULT_OPEN_METEO_GEOCODING_ENDPOINT;
        private URI getEndpoint = DEFAULT_OPEN_METEO_GEOCODING_GET_ENDPOINT;
        private int resultCount = DEFAULT_OPEN_METEO_GEOCODING_RESULT_COUNT;
        private String language = DEFAULT_OPEN_METEO_GEOCODING_LANGUAGE;

        public URI getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(URI endpoint) {
            this.endpoint = requireNonNull(endpoint, "moon.open-meteo.geocoding.endpoint");
        }

        public URI getGetEndpoint() {
            return getEndpoint;
        }

        public void setGetEndpoint(URI getEndpoint) {
            this.getEndpoint = requireNonNull(getEndpoint, "moon.open-meteo.geocoding.get-endpoint");
        }

        public int getResultCount() {
            return resultCount;
        }

        public void setResultCount(int resultCount) {
            this.resultCount = requirePositive(resultCount, "moon.open-meteo.geocoding.result-count");
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = requireNonBlank(language, "moon.open-meteo.geocoding.language");
        }
    }

    public static final class Forecast {
        private URI endpoint = DEFAULT_OPEN_METEO_FORECAST_ENDPOINT;

        public URI getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(URI endpoint) {
            this.endpoint = requireNonNull(endpoint, "moon.open-meteo.forecast.endpoint");
        }
    }

    public static final class Cache {
        private final GeocodingCache geocoding = new GeocodingCache();
        private final WeatherCache weather = new WeatherCache();

        public GeocodingCache getGeocoding() {
            return geocoding;
        }

        public WeatherCache getWeather() {
            return weather;
        }
    }

    public static final class GeocodingCache {
        private long maximumSize = DEFAULT_GEOCODING_CACHE_MAXIMUM_SIZE;
        private Duration resolvedTtl = DEFAULT_GEOCODING_CACHE_RESOLVED_TTL;
        private Duration ambiguousTtl = DEFAULT_GEOCODING_CACHE_AMBIGUOUS_TTL;
        private Duration notFoundTtl = DEFAULT_GEOCODING_CACHE_NOT_FOUND_TTL;
        private Duration temporarilyUnavailableTtl = DEFAULT_GEOCODING_CACHE_TEMPORARILY_UNAVAILABLE_TTL;

        public long getMaximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(long maximumSize) {
            this.maximumSize = requirePositive(maximumSize, "moon.cache.geocoding.maximum-size");
        }

        public Duration getResolvedTtl() {
            return resolvedTtl;
        }

        public void setResolvedTtl(Duration resolvedTtl) {
            this.resolvedTtl = requirePositive(resolvedTtl, "moon.cache.geocoding.resolved-ttl");
        }

        public Duration getAmbiguousTtl() {
            return ambiguousTtl;
        }

        public void setAmbiguousTtl(Duration ambiguousTtl) {
            this.ambiguousTtl = requirePositive(ambiguousTtl, "moon.cache.geocoding.ambiguous-ttl");
        }

        public Duration getNotFoundTtl() {
            return notFoundTtl;
        }

        public void setNotFoundTtl(Duration notFoundTtl) {
            this.notFoundTtl = requirePositive(notFoundTtl, "moon.cache.geocoding.not-found-ttl");
        }

        public Duration getTemporarilyUnavailableTtl() {
            return temporarilyUnavailableTtl;
        }

        public void setTemporarilyUnavailableTtl(Duration temporarilyUnavailableTtl) {
            this.temporarilyUnavailableTtl = requirePositive(
                    temporarilyUnavailableTtl,
                    "moon.cache.geocoding.temporarily-unavailable-ttl");
        }
    }

    public static final class WeatherCache {
        private long maximumSize = DEFAULT_WEATHER_CACHE_MAXIMUM_SIZE;
        private Duration availableTtl = DEFAULT_WEATHER_CACHE_AVAILABLE_TTL;
        private Duration unavailableTtl = DEFAULT_WEATHER_CACHE_UNAVAILABLE_TTL;

        public long getMaximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(long maximumSize) {
            this.maximumSize = requirePositive(maximumSize, "moon.cache.weather.maximum-size");
        }

        public Duration getAvailableTtl() {
            return availableTtl;
        }

        public void setAvailableTtl(Duration availableTtl) {
            this.availableTtl = requirePositive(availableTtl, "moon.cache.weather.available-ttl");
        }

        public Duration getUnavailableTtl() {
            return unavailableTtl;
        }

        public void setUnavailableTtl(Duration unavailableTtl) {
            this.unavailableTtl = requirePositive(unavailableTtl, "moon.cache.weather.unavailable-ttl");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static <T> T requireNonNull(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static String requireNonBlank(String value, String name) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return normalized;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration duration = requireNonNull(value, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return duration;
    }

    private static int requireZeroOrPositive(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be zero or greater.");
        }
        return value;
    }

    private static Duration requireZeroOrPositive(Duration value, String name) {
        Duration duration = requireNonNull(value, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be zero or greater.");
        }
        return duration;
    }
}
