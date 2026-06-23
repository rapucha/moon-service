package dev.moonservice.backend.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProviderLocationIdTest {
    @Test
    void serializesProviderNamespaceAndProviderId() {
        ProviderLocationId providerLocationId = new ProviderLocationId(LocationProvider.OPEN_METEO, "3067696");

        assertEquals("openmeteo:3067696", providerLocationId.serialized());
    }

    @Test
    void trimsProviderId() {
        ProviderLocationId providerLocationId = new ProviderLocationId(LocationProvider.OPEN_METEO, " prague-cz ");

        assertEquals("prague-cz", providerLocationId.providerId());
        assertEquals("openmeteo:prague-cz", providerLocationId.serialized());
    }

    @Test
    void rejectsBlankProviderId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderLocationId(LocationProvider.OPEN_METEO, " "));
    }

    @Test
    void rejectsProviderIdContainingNamespaceSeparator() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderLocationId(LocationProvider.OPEN_METEO, "openmeteo:3067696"));
    }
}
