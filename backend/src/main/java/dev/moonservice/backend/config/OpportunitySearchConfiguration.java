package dev.moonservice.backend.config;

import dev.moonservice.backend.location.CachingLocationResolver;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.OpenMeteoGeocodingClient;
import dev.moonservice.backend.observability.ObservedLocationResolver;
import dev.moonservice.backend.observability.ObservedWeatherForecastProvider;
import dev.moonservice.backend.observability.ObservingOpenMeteoTransport;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import dev.moonservice.backend.openmeteo.OpenMeteoTransport;
import dev.moonservice.backend.openmeteo.RestClientOpenMeteoTransport;
import dev.moonservice.backend.openmeteo.RetryingOpenMeteoTransport;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.opportunity.scoring.JvmScoringOpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
import dev.moonservice.backend.weather.CachingWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.backend.weather.openmeteo.OpenMeteoWeatherClient;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

@Configuration
class OpportunitySearchConfiguration {
    private static final Duration OPEN_METEO_TIMEOUT = Duration.ofSeconds(3);
    private static final int OPEN_METEO_MAX_TRANSPORT_RETRIES = 1;
    private static final Duration OPEN_METEO_MAX_RETRY_AFTER = Duration.ofSeconds(1);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PreviewEvaluator previewEvaluator() {
        return new PreviewEvaluator();
    }

    @Bean
    OpenMeteoObservability openMeteoObservability() {
        return new OpenMeteoObservability();
    }

    @Bean
    OpportunitySearchEngine opportunitySearchEngine(
            PreviewEvaluator previewEvaluator,
            WeatherForecastProvider weatherForecastProvider
    ) {
        return new JvmScoringOpportunitySearchEngine(previewEvaluator, weatherForecastProvider);
    }

    @Bean
    @ConditionalOnMissingBean(LocationResolver.class)
    CachingLocationResolver locationResolver(
            @Value("${moon.location.resolver:}") String resolver,
            OpenMeteoObservability openMeteoObservability
    ) {
        requireOpenMeteoProvider("moon.location.resolver", resolver);
        OpenMeteoObservability.ProviderMetrics geocodingMetrics = openMeteoObservability.geocoding();
        return CachingLocationResolver.withDefaults(new ObservedLocationResolver(
                new OpenMeteoGeocodingClient(openMeteoTransport(geocodingMetrics)),
                geocodingMetrics));
    }

    @Bean
    @ConditionalOnMissingBean(WeatherForecastProvider.class)
    CachingWeatherForecastProvider weatherForecastProvider(
            @Value("${moon.weather.provider:}") String provider,
            OpenMeteoObservability openMeteoObservability
    ) {
        requireOpenMeteoProvider("moon.weather.provider", provider);
        OpenMeteoObservability.ProviderMetrics weatherMetrics = openMeteoObservability.weather();
        return CachingWeatherForecastProvider.withDefaults(new ObservedWeatherForecastProvider(
                new OpenMeteoWeatherClient(openMeteoTransport(weatherMetrics)),
                weatherMetrics));
    }

    @Bean
    OpportunitySearchDefaults opportunitySearchDefaults(Clock clock) {
        return new OpportunitySearchDefaults(clock);
    }

    private static OpenMeteoTransport openMeteoTransport(OpenMeteoObservability.ProviderMetrics metrics) {
        return new RetryingOpenMeteoTransport(
                new ObservingOpenMeteoTransport(
                        new RestClientOpenMeteoTransport(RestClient.builder(), OPEN_METEO_TIMEOUT),
                        metrics),
                OPEN_METEO_MAX_TRANSPORT_RETRIES,
                OPEN_METEO_MAX_RETRY_AFTER,
                metrics::recordRetry);
    }

    private static void requireOpenMeteoProvider(String propertyName, String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(propertyName + " is required. Use open-meteo.");
        }
        if (!"open-meteo".equals(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported " + propertyName + " value: " + rawValue + ". Use open-meteo.");
        }
    }
}
