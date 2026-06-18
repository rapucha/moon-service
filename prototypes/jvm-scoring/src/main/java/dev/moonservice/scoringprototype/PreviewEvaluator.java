package dev.moonservice.scoringprototype;

import com.fasterxml.jackson.databind.JsonNode;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.input.RequestConfigReader;
import dev.moonservice.scoringprototype.output.ResponseFormatter;
import dev.moonservice.scoringprototype.service.OpportunityService;
import dev.moonservice.scoringprototype.service.PrototypeResult;

public final class PreviewEvaluator {
    public String evaluateJson(JsonNode request) {
        PrototypeConfig config = RequestConfigReader.fromJson(request);
        PrototypeResult result = new OpportunityService().evaluate(config);
        return new ResponseFormatter().format(result);
    }
}
