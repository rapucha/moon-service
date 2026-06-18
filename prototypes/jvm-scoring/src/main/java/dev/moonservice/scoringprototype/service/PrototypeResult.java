package dev.moonservice.scoringprototype.service;

import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ScoredWindow;

import java.util.List;

public record PrototypeResult(
        PrototypeConfig config,
        int candidateWindowsEvaluated,
        List<ScoredWindow> opportunities
) {
}
