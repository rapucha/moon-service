package dev.moonservice.backend.opportunity.prototype;

import dev.moonservice.backend.opportunity.OpportunitySearchEngine;
import dev.moonservice.backend.opportunity.OpportunitySearchRequest;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class PrototypeOpportunitySearchEngine implements OpportunitySearchEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PreviewEvaluator previewEvaluator;

    public PrototypeOpportunitySearchEngine(PreviewEvaluator previewEvaluator) {
        this.previewEvaluator = previewEvaluator;
    }

    @Override
    public String search(OpportunitySearchRequest request) {
        ObjectNode prototypeRequest = MAPPER.createObjectNode();
        prototypeRequest.put("locationId", request.locationId());
        prototypeRequest.put("start", request.start());
        prototypeRequest.put("forecastHorizonDays", request.forecastHorizonDays());
        prototypeRequest.put("maxMoonAltitudeDegrees", request.maxMoonAltitudeDegrees());
        prototypeRequest.put("limit", request.limit());
        return previewEvaluator.evaluateJson(prototypeRequest);
    }
}
