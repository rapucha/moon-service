package dev.moonservice.backend.config;

import dev.moonservice.backend.opportunity.FixtureLocationResolver;
import dev.moonservice.backend.opportunity.LocationResolver;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.opportunity.OpportunitySearchEngine;
import dev.moonservice.backend.opportunity.prototype.PrototypeOpportunitySearchEngine;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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
    LocationResolver locationResolver() {
        return new FixtureLocationResolver();
    }

    @Bean
    OpportunitySearchDefaults opportunitySearchDefaults(Clock clock) {
        return new OpportunitySearchDefaults(clock);
    }
}
