package dev.moonservice.scoringprototype;

final class Locations {
    static final Location PRAGUE = new Location(
            "prague-cz",
            "real_location",
            "openmeteo:prague-cz",
            "Prague / Praha, Czech Republic",
            50.08804,
            14.42076,
            202.0,
            "Europe/Prague",
            "CZ"
    );

    private Locations() {
    }

    static Location requireFixture(String slug) {
        if (!PRAGUE.slug().equals(slug)) {
            throw new UsageException("Unsupported location for this prototype: " + slug);
        }
        return PRAGUE;
    }
}
