package dev.moonservice.backend.config;

import dev.moonservice.backend.opportunity.OpportunitySearchEngine;
import dev.moonservice.backend.opportunity.prototype.PrototypeOpportunitySearchEngine;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpportunitySearchConfiguration {
    @Bean
    PreviewEvaluator previewEvaluator() {
        return new PreviewEvaluator();
    }

    @Bean
    OpportunitySearchEngine opportunitySearchEngine(PreviewEvaluator previewEvaluator) {
        return new PrototypeOpportunitySearchEngine(previewEvaluator);
    }
}
