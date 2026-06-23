package dev.moonservice.backend.config;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.OpenMeteoGeocodingClient;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.opportunity.prototype.PrototypeOpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
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
    OpportunitySearchEngine opportunitySearchEngine(PreviewEvaluator previewEvaluator) {
        return new PrototypeOpportunitySearchEngine(previewEvaluator);
    }

    @Bean
    @ConditionalOnMissingBean(LocationResolver.class)
    LocationResolver locationResolver(@Value("${moon.location.resolver:}") String resolver) {
        return switch (LocationResolverMode.from(resolver)) {
            case OPEN_METEO -> new OpenMeteoGeocodingClient();
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
}
