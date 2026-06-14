package dev.moonservice.prototype;

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
