package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.output.OpportunityIds;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MoonWindow(
        Location location,
        String kind,
        Instant passStartsAt,
        Instant passEndsAt,
        Instant startsAt,
        MoonSample start,
        MoonSample suggested,
        MoonSample end,
        Instant endsAt,
        List<MoonSample> passPathSamples,
        List<MoonSample> pathSamples
) {
    public MoonWindow {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(passStartsAt, "passStartsAt");
        Objects.requireNonNull(passEndsAt, "passEndsAt");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(suggested, "suggested");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(endsAt, "endsAt");
        if (passEndsAt.isBefore(passStartsAt)) {
            throw new IllegalArgumentException("passEndsAt must not be before passStartsAt.");
        }
        if (startsAt.isBefore(passStartsAt) || endsAt.isAfter(passEndsAt)) {
            throw new IllegalArgumentException("window must be inside Moon pass.");
        }
        if (!start.instant().equals(startsAt)) {
            throw new IllegalArgumentException("start sample instant must match startsAt.");
        }
        if (!end.instant().equals(endsAt)) {
            throw new IllegalArgumentException("end sample instant must match endsAt.");
        }
        passPathSamples = List.copyOf(passPathSamples);
        pathSamples = List.copyOf(pathSamples);
    }

    public String id() {
        return OpportunityIds.format(location.slug(), suggested.instant());
    }

    public String passId() {
        return OpportunityIds.format(location.slug() + "-pass", passStartsAt);
    }
}
