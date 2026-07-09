package dev.moonservice.backend.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
final class BuildRevision {
    static final String LOCAL_REVISION = "local";
    private static final Pattern SAFE_REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");

    private final String value;

    BuildRevision(@Value("${moon.build.revision:local}") String rawValue) {
        this.value = normalize(rawValue);
    }

    String value() {
        return value;
    }

    private static String normalize(String rawValue) {
        String value = rawValue == null ? "" : rawValue.strip();
        if (value.isEmpty()) {
            return LOCAL_REVISION;
        }
        if (!SAFE_REVISION.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "moon.build.revision must be 1-128 safe revision characters: letters, digits, '.', '_', '+', or '-'.");
        }
        return value;
    }
}
