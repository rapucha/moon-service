package dev.moonservice.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.moonservice.backend.location.CachingLocationResolver;
import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.observability.CacheMetricsSource;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import dev.moonservice.backend.observability.quota.ProviderQuotaMonitor;
import dev.moonservice.backend.weather.CachingWeatherForecastProvider;
import dev.moonservice.backend.weather.TestWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;

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
                    assertThat(context).hasSingleBean(ProviderQuotaMonitor.class);
                    assertThat(context).hasSingleBean(OpenMeteoObservability.class);
                    assertThat(context.getBean(ProviderQuotaMonitor.class).snapshots())
                            .containsKeys(
                                    OpenMeteoObservability.GEOCODING_OPERATION.id(),
                                    OpenMeteoObservability.WEATHER_OPERATION.id());
                    assertThat(context.getBeansOfType(CacheMetricsSource.class))
                            .containsKeys("locationResolver", "weatherForecastProvider");
                });
    }

    @Test
    void exposesTypedRuntimePropertiesWithDefaults() {
        contextRunner
                .withBean(LocationResolver.class, () -> query -> LocationResolution.notFound())
                .withBean(WeatherForecastProvider.class, TestWeatherForecastProvider::new)
                .run(context -> {
                    MoonRuntimeProperties properties = context.getBean(MoonRuntimeProperties.class);

                    assertThat(properties.getOpenMeteo().getTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getOpenMeteo().getMaxTransportRetries()).isEqualTo(1);
                    assertThat(properties.getOpenMeteo().getMaxRetryAfter()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.getOpenMeteo().getGeocoding().getEndpoint())
                            .isEqualTo(URI.create("https://geocoding-api.open-meteo.com/v1/search"));
                    assertThat(properties.getOpenMeteo().getGeocoding().getResultCount()).isEqualTo(10);
                    assertThat(properties.getOpenMeteo().getForecast().getEndpoint())
                            .isEqualTo(URI.create("https://api.open-meteo.com/v1/forecast"));
                    assertThat(properties.getCache().getGeocoding().getMaximumSize()).isEqualTo(2_000);
                    assertThat(properties.getCache().getGeocoding().getResolvedTtl())
                            .isEqualTo(Duration.ofHours(24));
                    assertThat(properties.getCache().getWeather().getMaximumSize()).isEqualTo(1_000);
                    assertThat(properties.getCache().getWeather().getAvailableTtl())
                            .isEqualTo(Duration.ofHours(1));
                    assertThat(properties.getResourceLimits().getWholeSiteCapacity()).isEqualTo(40);
                    assertThat(properties.getResourceLimits().getWholeSiteRefillInterval())
                            .isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.getResourceLimits().getProviderLookupCapacity()).isEqualTo(10);
                    assertThat(properties.getResourceLimits().getProviderLookupRefillInterval())
                            .isEqualTo(Duration.ofMinutes(1));
                    assertThat(properties.getResourceLimits().getOpportunityConcurrency()).isEqualTo(2);
                });
    }

    @Test
    void bindsTypedRuntimePropertyOverrides() {
        contextRunner
                .withBean(LocationResolver.class, () -> query -> LocationResolution.notFound())
                .withBean(WeatherForecastProvider.class, TestWeatherForecastProvider::new)
                .withPropertyValues(
                        "moon.open-meteo.timeout=PT2S",
                        "moon.open-meteo.max-transport-retries=3",
                        "moon.open-meteo.max-retry-after=PT0S",
                        "moon.open-meteo.geocoding.endpoint=https://example.test/geocoding/search",
                        "moon.open-meteo.geocoding.get-endpoint=https://example.test/geocoding/get",
                        "moon.open-meteo.geocoding.result-count=4",
                        "moon.open-meteo.geocoding.language=cs",
                        "moon.open-meteo.forecast.endpoint=https://example.test/forecast",
                        "moon.cache.geocoding.maximum-size=123",
                        "moon.cache.geocoding.not-found-ttl=PT5M",
                        "moon.cache.weather.maximum-size=456",
                        "moon.cache.weather.unavailable-ttl=PT45S",
                        "moon.resource-limits.whole-site-capacity=80",
                        "moon.resource-limits.whole-site-refill-interval=PT2S",
                        "moon.resource-limits.provider-lookup-capacity=20",
                        "moon.resource-limits.provider-lookup-refill-interval=PT2M",
                        "moon.resource-limits.opportunity-concurrency=4")
                .run(context -> {
                    MoonRuntimeProperties properties = context.getBean(MoonRuntimeProperties.class);

                    assertThat(properties.getOpenMeteo().getTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.getOpenMeteo().getMaxTransportRetries()).isEqualTo(3);
                    assertThat(properties.getOpenMeteo().getMaxRetryAfter()).isEqualTo(Duration.ZERO);
                    assertThat(properties.getOpenMeteo().getGeocoding().getEndpoint())
                            .isEqualTo(URI.create("https://example.test/geocoding/search"));
                    assertThat(properties.getOpenMeteo().getGeocoding().getGetEndpoint())
                            .isEqualTo(URI.create("https://example.test/geocoding/get"));
                    assertThat(properties.getOpenMeteo().getGeocoding().getResultCount()).isEqualTo(4);
                    assertThat(properties.getOpenMeteo().getGeocoding().getLanguage()).isEqualTo("cs");
                    assertThat(properties.getOpenMeteo().getForecast().getEndpoint())
                            .isEqualTo(URI.create("https://example.test/forecast"));
                    assertThat(properties.getCache().getGeocoding().getMaximumSize()).isEqualTo(123);
                    assertThat(properties.getCache().getGeocoding().getNotFoundTtl()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.getCache().getWeather().getMaximumSize()).isEqualTo(456);
                    assertThat(properties.getCache().getWeather().getUnavailableTtl()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(properties.getResourceLimits().getWholeSiteCapacity()).isEqualTo(80);
                    assertThat(properties.getResourceLimits().getWholeSiteRefillInterval())
                            .isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.getResourceLimits().getProviderLookupCapacity()).isEqualTo(20);
                    assertThat(properties.getResourceLimits().getProviderLookupRefillInterval())
                            .isEqualTo(Duration.ofMinutes(2));
                    assertThat(properties.getResourceLimits().getOpportunityConcurrency()).isEqualTo(4);
                });
    }

    @Test
    void configuresProviderQuotaLimitsAndAdditionalOperations() {
        contextRunner
                .withBean(LocationResolver.class, () -> query -> LocationResolution.notFound())
                .withBean(WeatherForecastProvider.class, TestWeatherForecastProvider::new)
                .withPropertyValues(
                        "moon.provider-quotas.operations.open-meteo-weather.hourly-limit=38",
                        "moon.provider-quotas.operations.open-meteo-weather.daily-limit=23",
                        "moon.provider-quotas.operations.open-meteo-weather.monthly-limit=20",
                        "moon.provider-quotas.operations.fictional-location-llm.provider=example-llm",
                        "moon.provider-quotas.operations.fictional-location-llm.operation=fictional-location-resolution",
                        "moon.provider-quotas.operations.fictional-location-llm.daily-limit=100")
                .run(context -> {
                    ProviderQuotaMonitor monitor = context.getBean(ProviderQuotaMonitor.class);

                    ProviderQuotaMonitor.ProviderQuotaSnapshot weather = monitor.snapshots()
                            .get(OpenMeteoObservability.WEATHER_OPERATION.id());
                    assertThat(weather.provider()).isEqualTo("open-meteo");
                    assertThat(weather.operation()).isEqualTo("weather");
                    assertThat(weather.usage().hourly().limit()).isEqualTo(38L);
                    assertThat(weather.usage().daily().limit()).isEqualTo(23L);
                    assertThat(weather.usage().monthly().limit()).isEqualTo(20L);

                    ProviderQuotaMonitor.ProviderQuotaSnapshot fictionalLlm = monitor.snapshots()
                            .get("fictional-location-llm");
                    assertThat(fictionalLlm.provider()).isEqualTo("example-llm");
                    assertThat(fictionalLlm.operation()).isEqualTo("fictional-location-resolution");
                    assertThat(fictionalLlm.usage().hourly().knownLimit()).isFalse();
                    assertThat(fictionalLlm.usage().daily().limit()).isEqualTo(100L);
                });
    }

    @Test
    void rejectsAdditionalProviderQuotaOperationWithoutProviderMetadata() {
        contextRunner
                .withBean(LocationResolver.class, () -> query -> LocationResolution.notFound())
                .withBean(WeatherForecastProvider.class, TestWeatherForecastProvider::new)
                .withPropertyValues("moon.provider-quotas.operations.fictional-location-llm.daily-limit=100")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Provider operation provider must not be blank.");
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
