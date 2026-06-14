package dev.moonservice.scoringprototype;

import java.util.List;

record RejectedWindow(
        String id,
        Integer score,
        List<String> reasons
) {
}
