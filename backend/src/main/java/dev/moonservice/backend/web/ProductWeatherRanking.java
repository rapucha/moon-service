package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import tools.jackson.databind.JsonNode;

enum ProductWeatherRanking {
    BALANCED(WeatherRanking.BALANCED),
    PREFER_CLEAR(WeatherRanking.PREFER_CLEAR),
    IGNORE_WEATHER(WeatherRanking.IGNORE_WEATHER);

    private final WeatherRanking scoringValue;

    ProductWeatherRanking(WeatherRanking scoringValue) {
        this.scoringValue = scoringValue;
    }

    static ProductWeatherRanking parseOptional(JsonNode root) {
        JsonNode value = root.get("weatherRanking");
        if (value == null) {
            return null;
        }
        if (value.isString()) {
            for (ProductWeatherRanking candidate : values()) {
                if (candidate.scoringValue.wireValue().equals(value.asString())) {
                    return candidate;
                }
            }
        }
        throw new InvalidOpportunitySearchRequestException(
                "weatherRanking must be balanced, prefer_clear, or ignore_weather.");
    }

    WeatherRanking scoringValue() {
        return scoringValue;
    }
}
