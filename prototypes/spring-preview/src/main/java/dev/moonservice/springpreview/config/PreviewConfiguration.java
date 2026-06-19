package dev.moonservice.springpreview.config;

import dev.moonservice.scoringprototype.PreviewEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PreviewConfiguration {
    @Bean
    PreviewEvaluator previewEvaluator() {
        return new PreviewEvaluator();
    }
}
