package dev.moonservice.scoringprototype.scoring;

import java.util.List;

public record RejectedWindow(
        String id,
        Integer score,
        List<String> reasons
) {
}
