package dev.moonservice.backend.location;

public enum LocationProvider {
    FIXTURE("fixture"),
    OPEN_METEO("openmeteo");

    private final String id;

    LocationProvider(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
