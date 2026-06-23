package dev.moonservice.backend.config;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.OpenMeteoGeocodingClient;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.opportunity.scoring.JvmScoringOpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.backend.weather.openmeteo.OpenMeteoWeatherClient;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Locale;

@Configuration
class OpportunitySearchConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PreviewEvaluator previewEvaluator() {
        return new PreviewEvaluator();
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
    LocationResolver locationResolver(@Value("${moon.location.resolver:}") String resolver) {
        return switch (LocationResolverMode.from(resolver)) {
            case OPEN_METEO -> new OpenMeteoGeocodingClient();
        };
    }

    @Bean
    @ConditionalOnMissingBean(WeatherForecastProvider.class)
    WeatherForecastProvider weatherForecastProvider(@Value("${moon.weather.provider:}") String provider) {
        return switch (WeatherProviderMode.from(provider)) {
            case OPEN_METEO -> new OpenMeteoWeatherClient();
        };
    }

    @Bean
    OpportunitySearchDefaults opportunitySearchDefaults(Clock clock) {
        return new OpportunitySearchDefaults(clock);
    }

    private enum LocationResolverMode {
        OPEN_METEO("open-meteo");

        private final String propertyValue;

        LocationResolverMode(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        private static LocationResolverMode from(String rawValue) {
            String normalized = rawValue == null ? "" : rawValue.strip().toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(
                        "moon.location.resolver is required. Use open-meteo.");
            }
            for (LocationResolverMode mode : values()) {
                if (mode.propertyValue.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "Unsupported moon.location.resolver value: " + rawValue + ". Use open-meteo.");
        }
    }

    private enum WeatherProviderMode {
        OPEN_METEO("open-meteo");

        private final String propertyValue;

        WeatherProviderMode(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        private static WeatherProviderMode from(String rawValue) {
            String normalized = rawValue == null ? "" : rawValue.strip().toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(
                        "moon.weather.provider is required. Use open-meteo.");
            }
            for (WeatherProviderMode mode : values()) {
                if (mode.propertyValue.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "Unsupported moon.weather.provider value: " + rawValue + ". Use open-meteo.");
        }
    }
}
