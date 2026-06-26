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
        requireOpenMeteoProvider("moon.location.resolver", resolver);
        return new OpenMeteoGeocodingClient();
    }

    @Bean
    @ConditionalOnMissingBean(WeatherForecastProvider.class)
    WeatherForecastProvider weatherForecastProvider(@Value("${moon.weather.provider:}") String provider) {
        requireOpenMeteoProvider("moon.weather.provider", provider);
        return new OpenMeteoWeatherClient();
    }

    @Bean
    OpportunitySearchDefaults opportunitySearchDefaults(Clock clock) {
        return new OpportunitySearchDefaults(clock);
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
