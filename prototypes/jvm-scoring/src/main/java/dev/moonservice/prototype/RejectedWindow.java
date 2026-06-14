package dev.moonservice.prototype;

import java.util.List;

record RejectedWindow(
        String id,
        Integer score,
        List<String> reasons
) {
}
