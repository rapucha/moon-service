package dev.moonservice.backend.location.fixture;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FixtureLocationResolver implements LocationResolver {
    private static final ResolvedLocation PRAGUE = new ResolvedLocation(
            "prague-cz",
            new ProviderLocationId(LocationProvider.FIXTURE, "prague-cz"),
            "Prague, Czechia",
            50.08804,
            14.42076,
            202,
            ZoneId.of("Europe/Prague"),
            "CZ");
    private static final ResolvedLocation SPRINGFIELD_MISSOURI = new ResolvedLocation(
            "springfield-mo-us",
            new ProviderLocationId(LocationProvider.FIXTURE, "springfield-mo-us"),
            "Springfield, Missouri, United States",
            37.21533,
            -93.29824,
            396,
            ZoneId.of("America/Chicago"),
            "US");
    private static final ResolvedLocation SPRINGFIELD_ILLINOIS = new ResolvedLocation(
            "springfield-il-us",
            new ProviderLocationId(LocationProvider.FIXTURE, "springfield-il-us"),
            "Springfield, Illinois, United States",
            39.80172,
            -89.64371,
            182,
            ZoneId.of("America/Chicago"),
            "US");
    private static final Map<String, LocationResolution> FIXTURES = Map.of(
            "praha", LocationResolution.resolved(PRAGUE),
            "prague", LocationResolution.resolved(PRAGUE),
            "prague-cz", LocationResolution.resolved(PRAGUE),
            "springfield", LocationResolution.ambiguous(List.of(SPRINGFIELD_MISSOURI, SPRINGFIELD_ILLINOIS))
    );

    @Override
    public LocationResolution resolve(LocationQuery query) {
        return FIXTURES.getOrDefault(query.text().toLowerCase(Locale.ROOT), LocationResolution.notFound());
    }
}
