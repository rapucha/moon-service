package dev.moonservice.backend.location.fixture;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.ResolvedLocation;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class FixtureLocationResolver implements LocationResolver {
    private static final ResolvedLocation PRAGUE = new ResolvedLocation(
            "prague-cz",
            "Prague, Czechia",
            ZoneId.of("Europe/Prague"));
    private static final Map<String, ResolvedLocation> FIXTURES = Map.of(
            "praha", PRAGUE,
            "prague", PRAGUE,
            "prague-cz", PRAGUE
    );

    @Override
    public Optional<ResolvedLocation> resolve(String query) {
        return Optional.ofNullable(FIXTURES.get(query.toLowerCase(Locale.ROOT)));
    }
}
