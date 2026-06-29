package dev.moonservice.backend.config;

import dev.moonservice.backend.location.CachingLocationResolver;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.OpenMeteoGeocodingClient;
import dev.moonservice.backend.observability.ObservedLocationResolver;
import dev.moonservice.backend.observability.ObservedWeatherForecastProvider;
import dev.moonservice.backend.observability.ObservingOpenMeteoTransport;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import dev.moonservice.backend.openmeteo.OpenMeteoTransport;
import dev.moonservice.backend.openmeteo.RestClientOpenMeteoTransport;
import dev.moonservice.backend.openmeteo.RetryingOpenMeteoTransport;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.opportunity.scoring.JvmScoringOpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
import dev.moonservice.backend.weather.CachingWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.backend.weather.openmeteo.OpenMeteoWeatherClient;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.Locale;

@Configuration
@EnableConfigurationProperties(MoonRuntimeProperties.class)
class OpportunitySearchConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PreviewEvaluator previewEvaluator() {
        return new PreviewEvaluator();
    }

    @Bean
    OpenMeteoObservability openMeteoObservability() {
        return new OpenMeteoObservability();
    }

    @Bean
    OpportunitySearchEngine opportunitySearchEngine(
            PreviewEvaluator previewEvaluator,
            WeatherForecastProvider weatherForecastProvider
    ) {
        return new JvmScoringOpportunitySearchEngine(previewEvaluator, weatherForecastProvider);
    }

    @Bean
    @ConditionalOnMissingBean(LocationResolver.class)
    CachingLocationResolver locationResolver(
            MoonRuntimeProperties properties,
            OpenMeteoObservability openMeteoObservability
    ) {
        requireOpenMeteoProvider("moon.location.resolver", properties.getLocation().getResolver());
        MoonRuntimeProperties.OpenMeteo openMeteo = properties.getOpenMeteo();
        MoonRuntimeProperties.Geocoding geocoding = openMeteo.getGeocoding();
        MoonRuntimeProperties.GeocodingCache cache = properties.getCache().getGeocoding();
        OpenMeteoObservability.ProviderMetrics geocodingMetrics = openMeteoObservability.geocoding();
        OpenMeteoTransport transport = openMeteoTransport(geocodingMetrics, openMeteo);
        LocationResolver openMeteoResolver = new OpenMeteoGeocodingClient(
                geocoding.getEndpoint(),
                geocoding.getGetEndpoint(),
                geocoding.getLanguage(),
                geocoding.getResultCount(),
                transport);
        LocationResolver observedResolver = new ObservedLocationResolver(openMeteoResolver, geocodingMetrics);
        return CachingLocationResolver.withSettings(
                observedResolver,
                cache.getMaximumSize(),
                cache.getResolvedTtl(),
                cache.getAmbiguousTtl(),
                cache.getNotFoundTtl(),
                cache.getTemporarilyUnavailableTtl());
    }

    @Bean
    @ConditionalOnMissingBean(WeatherForecastProvider.class)
    CachingWeatherForecastProvider weatherForecastProvider(
            MoonRuntimeProperties properties,
            OpenMeteoObservability openMeteoObservability
    ) {
        requireOpenMeteoProvider("moon.weather.provider", properties.getWeather().getProvider());
        MoonRuntimeProperties.OpenMeteo openMeteo = properties.getOpenMeteo();
        MoonRuntimeProperties.WeatherCache cache = properties.getCache().getWeather();
        OpenMeteoObservability.ProviderMetrics weatherMetrics = openMeteoObservability.weather();
        OpenMeteoTransport transport = openMeteoTransport(weatherMetrics, openMeteo);
        WeatherForecastProvider openMeteoProvider = new OpenMeteoWeatherClient(
                openMeteo.getForecast().getEndpoint(),
                transport);
        WeatherForecastProvider observedProvider = new ObservedWeatherForecastProvider(openMeteoProvider, weatherMetrics);
        return CachingWeatherForecastProvider.withSettings(
                observedProvider,
                cache.getMaximumSize(),
                cache.getAvailableTtl(),
                cache.getUnavailableTtl());
    }

    @Bean
    OpportunitySearchDefaults opportunitySearchDefaults(Clock clock) {
        return new OpportunitySearchDefaults(clock);
    }

    private static OpenMeteoTransport openMeteoTransport(
            OpenMeteoObservability.ProviderMetrics metrics,
            MoonRuntimeProperties.OpenMeteo properties
    ) {
        return new RetryingOpenMeteoTransport(
                new ObservingOpenMeteoTransport(
                        new RestClientOpenMeteoTransport(RestClient.builder(), properties.getTimeout()),
                        metrics),
                properties.getMaxTransportRetries(),
                properties.getMaxRetryAfter(),
                metrics::recordRetry);
    }

    private static void requireOpenMeteoProvider(String propertyName, String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(propertyName + " is required. Use open-meteo.");
        }
        if (!"open-meteo".equals(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported " + propertyName + " value: " + rawValue + ". Use open-meteo.");
        }
    }
}
