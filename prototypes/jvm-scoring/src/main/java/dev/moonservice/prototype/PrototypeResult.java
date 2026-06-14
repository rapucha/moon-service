package dev.moonservice.prototype;

import java.util.List;
import java.util.Map;

record PrototypeResult(
        PrototypeConfig config,
        List<MoonSample> samples,
        List<ScoredWindow> opportunities,
        List<RejectedWindow> rejected,
        Map<String, Integer> rejectedCounts
) {
}
