package dev.moonservice.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.OpenMeteoGeocodingClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpportunitySearchConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OpportunitySearchConfiguration.class);

    @Test
    void requiresLocationResolverConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("moon.location.resolver is required. Use open-meteo.");
        });
    }

    @Test
    void canSelectOpenMeteoLocationResolver() {
        contextRunner
                .withPropertyValues("moon.location.resolver=open-meteo")
                .run(context -> assertThat(context.getBean(LocationResolver.class))
                        .isInstanceOf(OpenMeteoGeocodingClient.class));
    }

    @Test
    void rejectsFixtureResolverValueAsRuntimeConfiguration() {
        contextRunner
                .withPropertyValues("moon.location.resolver=fixture")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Unsupported moon.location.resolver value: fixture. Use open-meteo.");
                });
    }

    @Test
    void rejectsUnsupportedLocationResolver() {
        contextRunner
                .withPropertyValues("moon.location.resolver=provider-x")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Unsupported moon.location.resolver value: provider-x. Use open-meteo.");
                });
    }

    @Test
    void backsOffWhenTestProvidesLocationResolverBean() {
        contextRunner
                .withBean(LocationResolver.class, () -> query -> LocationResolution.notFound())
                .run(context -> assertThat(context.getBean(LocationResolver.class).resolve(new LocationQuery("test")))
                        .isEqualTo(LocationResolution.notFound()));
    }
}
