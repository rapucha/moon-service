package dev.moonservice.scoringprototype;

import java.time.Instant;

final class OpportunityIds {
    private OpportunityIds() {
    }

    static String format(String locationSlug, Instant peak) {
        return locationSlug + "-" + peak.toString()
                .replace(":00Z", "Z")
                .replace(":", "");
    }
}
