package dev.moonservice.scoringprototype.output;

import java.time.Instant;

public final class OpportunityIds {
    private OpportunityIds() {
    }

    public static String format(String locationSlug, Instant peak) {
        return locationSlug + "-" + peak.toString()
                .replace(":00Z", "Z")
                .replace(":", "");
    }
}
