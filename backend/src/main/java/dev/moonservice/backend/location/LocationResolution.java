package dev.moonservice.backend.location;

import java.util.List;
import java.util.Optional;

public record LocationResolution(Status status, List<ResolvedLocation> candidates) {
    public LocationResolution {
        candidates = List.copyOf(candidates);
        if (status == Status.AMBIGUOUS && candidates.size() < 2) {
            throw new IllegalArgumentException("Ambiguous location resolution requires at least two candidates.");
        }
        if ((status == Status.RESOLVED || status == Status.NOT_FOUND || status == Status.TEMPORARILY_UNAVAILABLE)
                && candidates.size() > 1) {
            throw new IllegalArgumentException(status + " location resolution cannot have multiple candidates.");
        }
    }

    public static LocationResolution resolved(ResolvedLocation location) {
        return new LocationResolution(Status.RESOLVED, List.of(location));
    }

    public static LocationResolution ambiguous(List<ResolvedLocation> candidates) {
        return new LocationResolution(Status.AMBIGUOUS, candidates);
    }

    public static LocationResolution notFound() {
        return new LocationResolution(Status.NOT_FOUND, List.of());
    }

    public static LocationResolution temporarilyUnavailable() {
        return new LocationResolution(Status.TEMPORARILY_UNAVAILABLE, List.of());
    }

    public Optional<ResolvedLocation> singleCandidate() {
        if (status == Status.RESOLVED) {
            return Optional.of(candidates.getFirst());
        }
        return Optional.empty();
    }

    public boolean isAmbiguous() {
        return status == Status.AMBIGUOUS;
    }

    public boolean isTemporarilyUnavailable() {
        return status == Status.TEMPORARILY_UNAVAILABLE;
    }

    public enum Status {
        RESOLVED,
        AMBIGUOUS,
        NOT_FOUND,
        TEMPORARILY_UNAVAILABLE
    }
}
