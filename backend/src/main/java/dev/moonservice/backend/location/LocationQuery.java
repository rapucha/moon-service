package dev.moonservice.backend.location;

public record LocationQuery(String text) {
    public LocationQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Location query text must be provided.");
        }
    }
}
