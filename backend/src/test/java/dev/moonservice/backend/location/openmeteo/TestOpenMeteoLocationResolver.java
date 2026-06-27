package dev.moonservice.backend.location.openmeteo;

import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TestOpenMeteoLocationResolver implements LocationResolver {
    private static final ResolvedLocation PRAGUE = new ResolvedLocation(
            "prague-cz",
            new ProviderLocationId(LocationProvider.OPEN_METEO, "3067696"),
            "Prague, Czechia",
            50.08804,
            14.42076,
            202,
            ZoneId.of("Europe/Prague"),
            "CZ");
    private static final ResolvedLocation SPRINGFIELD_MISSOURI = new ResolvedLocation(
            "springfield-mo-us",
            new ProviderLocationId(LocationProvider.OPEN_METEO, "test-springfield-mo-us"),
            "Springfield, Missouri, United States",
            37.21533,
            -93.29824,
            396,
            ZoneId.of("America/Chicago"),
            "US");
    private static final ResolvedLocation SPRINGFIELD_ILLINOIS = new ResolvedLocation(
            "springfield-il-us",
            new ProviderLocationId(LocationProvider.OPEN_METEO, "test-springfield-il-us"),
            "Springfield, Illinois, United States",
            39.80172,
            -89.64371,
            182,
            ZoneId.of("America/Chicago"),
            "US");
    private static final ResolvedLocation AMSTERDAM = new ResolvedLocation(
            "amsterdam-nl",
            new ProviderLocationId(LocationProvider.OPEN_METEO, "2759794"),
            "Amsterdam, North Holland, Netherlands",
            52.37403,
            4.88969,
            13,
            ZoneId.of("Europe/Amsterdam"),
            "NL");
    private static final Map<String, LocationResolution> TEST_RESULTS = Map.of(
            "praha", LocationResolution.resolved(PRAGUE),
            "prague", LocationResolution.resolved(PRAGUE),
            "prague-cz", LocationResolution.resolved(PRAGUE),
            "amsterdam", LocationResolution.resolved(AMSTERDAM),
            "springfield", LocationResolution.ambiguous(List.of(SPRINGFIELD_MISSOURI, SPRINGFIELD_ILLINOIS))
    );
    private static final Map<String, LocationResolution> TEST_LOCATION_ID_RESULTS = Map.of(
            "prague-cz", LocationResolution.resolved(PRAGUE),
            "amsterdam-nl", LocationResolution.resolved(AMSTERDAM),
            "springfield-mo-us", LocationResolution.resolved(SPRINGFIELD_MISSOURI),
            "springfield-il-us", LocationResolution.resolved(SPRINGFIELD_ILLINOIS)
    );

    @Override
    public LocationResolution resolve(LocationQuery query) {
        return TEST_RESULTS.getOrDefault(query.text().toLowerCase(Locale.ROOT), LocationResolution.notFound());
    }

    @Override
    public LocationResolution resolveLocationId(String locationId) {
        return TEST_LOCATION_ID_RESULTS.getOrDefault(locationId, LocationResolution.notFound());
    }
}
