package dev.moonservice.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.moonservice.backend.location.CachingLocationResolver;
import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.observability.CacheMetricsSource;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import dev.moonservice.backend.weather.CachingWeatherForecastProvider;
import dev.moonservice.backend.weather.TestWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpportunitySearchConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OpportunitySearchConfiguration.class);

    @Test
    void requiresLocationResolverConfiguration() {
        contextRunner
                .withPropertyValues("moon.weather.provider=open-meteo")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("moon.location.resolver is required. Use open-meteo.");
                });
    }

    @Test
    void canSelectOpenMeteoLocationResolverWithProviderCallProtections() {
        contextRunner
                .withPropertyValues(
                        "moon.location.resolver=open-meteo",
                        "moon.weather.provider=open-meteo")
                .run(context -> assertThat(context.getBean(LocationResolver.class))
                        .isInstanceOf(CachingLocationResolver.class));
    }

    @Test
    void canSelectOpenMeteoWeatherProviderWithProviderCallProtections() {
        contextRunner
                .withPropertyValues(
                        "moon.location.resolver=open-meteo",
                        "moon.weather.provider=open-meteo")
                .run(context -> assertThat(context.getBean(WeatherForecastProvider.class))
                        .isInstanceOf(CachingWeatherForecastProvider.class));
    }

    @Test
    void exposesOpenMeteoObservabilityAndRuntimeCacheMetricsSources() {
        contextRunner
                .withPropertyValues(
                        "moon.location.resolver=open-meteo",
                        "moon.weather.provider=open-meteo")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenMeteoObservability.class);
                    assertThat(context.getBeansOfType(CacheMetricsSource.class))
                            .containsKeys("locationResolver", "weatherForecastProvider");
                });
    }

    @Test
    void rejectsFixtureResolverValueAsRuntimeConfiguration() {
        contextRunner
                .withPropertyValues(
                        "moon.location.resolver=fixture",
                        "moon.weather.provider=open-meteo")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Unsupported moon.location.resolver value: fixture. Use open-meteo.");
                });
    }

    @Test
    void rejectsUnsupportedLocationResolver() {
        contextRunner
                .withPropertyValues(
                        "moon.location.resolver=provider-x",
                        "moon.weather.provider=open-meteo")
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
                .withBean(WeatherForecastProvider.class, TestWeatherForecastProvider::new)
                .run(context -> assertThat(context.getBean(LocationResolver.class).resolve(new LocationQuery("test")))
                        .isEqualTo(LocationResolution.notFound()));
    }

    @Test
    void requiresWeatherProviderConfiguration() {
        contextRunner
                .withPropertyValues("moon.location.resolver=open-meteo")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("moon.weather.provider is required. Use open-meteo.");
                });
    }

    @Test
    void rejectsUnsupportedWeatherProvider() {
        contextRunner
                .withPropertyValues(
                        "moon.location.resolver=open-meteo",
                        "moon.weather.provider=provider-x")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Unsupported moon.weather.provider value: provider-x. Use open-meteo.");
                });
    }

    @Test
    void backsOffWhenTestProvidesWeatherProviderBean() {
        WeatherForecastProvider provider = new TestWeatherForecastProvider();
        contextRunner
                .withPropertyValues("moon.location.resolver=open-meteo")
                .withBean(WeatherForecastProvider.class, () -> provider)
                .run(context -> assertThat(context.getBean(WeatherForecastProvider.class))
                        .isSameAs(provider));
    }
}
