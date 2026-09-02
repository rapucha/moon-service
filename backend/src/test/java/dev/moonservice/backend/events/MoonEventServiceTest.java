package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.Candidates;
import dev.moonservice.backend.events.MoonEventResponse.DisplayInterval;
import dev.moonservice.backend.events.MoonEventResponse.EclipsePhase;
import dev.moonservice.backend.events.MoonEventResponse.EclipseShadow;
import dev.moonservice.backend.events.MoonEventResponse.EclipseShadowMoon;
import dev.moonservice.backend.events.MoonEventResponse.EclipseShadowSample;
import dev.moonservice.backend.events.MoonEventResponse.EventVisibility;
import dev.moonservice.backend.events.MoonEventResponse.FullMoonEvent;
import dev.moonservice.backend.events.MoonEventResponse.FullMoonQualifier;
import dev.moonservice.backend.events.MoonEventResponse.Interval;
import dev.moonservice.backend.events.MoonEventResponse.LocalViewing;
import dev.moonservice.backend.events.MoonEventResponse.LunarEclipseEvent;
import dev.moonservice.backend.events.MoonEventResponse.MoonPosition;
import dev.moonservice.backend.events.MoonEventResponse.MoonPath;
import dev.moonservice.backend.events.MoonEventResponse.MoonPathSample;
import dev.moonservice.backend.events.MoonEventResponse.PhaseVisibility;
import dev.moonservice.backend.events.MoonEventResponse.PreferenceAssessment;
import dev.moonservice.backend.events.MoonEventResponse.Status;
import dev.moonservice.backend.events.MoonEventResponse.Success;
import dev.moonservice.backend.events.MoonEventResponse.SunPosition;
import dev.moonservice.backend.events.MoonEventResponse.Weather;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.weather.HourlyWeather;
import dev.moonservice.backend.weather.HourlyWeatherForecast;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MoonEventServiceTest {
    private static final ResolvedLocation PRAGUE = new TestOpenMeteoLocationResolver()
            .resolveLocationId("prague-cz").singleCandidate().orElseThrow();

    @Test
    void resolvesOnceCombinesChronologicallyAndUsesOneWeatherLookup() {
        Instant generatedAt = Instant.parse("2026-08-30T10:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(generatedAt);
        LocationResolver resolver = resolved(PRAGUE);
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);
        when(weather.forecastFor(any(), any(), any())).thenReturn(
                new HourlyWeatherForecast(List.of(weatherHour("2026-09-01T02:00:00Z"))));
        LunarEclipseEventService eclipses = mock(LunarEclipseEventService.class);
        NearPerigeeFullMoonService fullMoons = mock(NearPerigeeFullMoonService.class);
        when(eclipses.discover(any(), any(), any(), any()))
                .thenReturn(List.of(eclipse(
                        "eclipse-later", "2026-09-01T02:30:00Z")));
        when(fullMoons.discover(any(), any(), any(), any())).thenReturn(List.of(
                fullMoon("full-earlier", "2026-09-01T02:10:00Z", true),
                fullMoon("full-no-local", "2027-01-22T12:17:50.281Z", false)));
        MoonEventService service = service(
                clock, resolver, weather, eclipses, fullMoons);

        Success response = (Success) service.search(
                "prague-cz", OpportunityPreferences.none(), 6, List.of("/unknown"), 3);

        assertThat(response.generatedAt()).isEqualTo(generatedAt.toString());
        assertThat(response.endsAt()).isEqualTo("2027-02-28T11:00:00Z");
        assertThat(response.location().displayName()).isEqualTo("Prague, Czechia");
        assertThat(response.ignoredPreferenceFields()).containsExactly("/unknown");
        assertThat(response.ignoredPreferenceFieldCount()).isEqualTo(3);
        assertThat(response.additionalIgnoredPreferenceFieldCount()).isEqualTo(2);
        assertThat(response.events()).extracting(event -> event.kind())
                .containsExactly("full_moon", "lunar_eclipse", "full_moon");
        FullMoonEvent earlier = (FullMoonEvent) response.events().getFirst();
        LunarEclipseEvent later = (LunarEclipseEvent) response.events().get(1);
        FullMoonEvent noLocal = (FullMoonEvent) response.events().get(2);
        assertThat(earlier.weather().status()).isEqualTo("available");
        assertThat(later.weather().status()).isEqualTo("available");
        assertThat(earlier.localViewing().moonPath().samples())
                .allSatisfy(sample -> assertThat(sample.shadow()).isNull());
        assertThat(later.localVisibility().moonPath().samples())
                .allSatisfy(sample -> assertThat(sample.shadow()).isNotNull());
        assertThat(noLocal.localViewing()).isNull();
        assertThat(noLocal.weather()).isNull();

        verify(clock, times(1)).instant();
        verify(resolver, times(1)).resolveLocationId("prague-cz");
        verify(weather, times(1)).forecastFor(any(), any(), any());
        verify(eclipses).discover(
                eq(generatedAt),
                eq(Instant.parse("2027-02-28T11:00:00Z")),
                any(),
                any());
        verify(fullMoons).discover(
                eq(generatedAt),
                eq(Instant.parse("2027-02-28T11:00:00Z")),
                any(),
                any());
    }

    @Test
    void ordersEqualObjectiveTimesByStableId() {
        Instant generatedAt = Instant.parse("2026-08-30T10:00:00Z");
        Clock clock = fixed(generatedAt);
        LunarEclipseEventService eclipses = mock(LunarEclipseEventService.class);
        NearPerigeeFullMoonService fullMoons = mock(NearPerigeeFullMoonService.class);
        when(eclipses.discover(any(), any(), any(), any()))
                .thenReturn(List.of(eclipse("z-id", "2027-01-22T12:17:50.281Z")));
        when(fullMoons.discover(any(), any(), any(), any()))
                .thenReturn(List.of(fullMoon(
                        "a-id", "2027-01-22T12:17:50.281Z", false)));

        Success response = (Success) service(
                clock,
                resolved(PRAGUE),
                mock(WeatherForecastProvider.class),
                eclipses,
                fullMoons).search(
                "prague-cz", OpportunityPreferences.none(), 18, List.of(), 0);

        assertThat(response.events()).extracting(event -> event.id())
                .containsExactly("a-id", "z-id");
    }

    @Test
    void skipsWeatherWhenNoSuggestionFallsInsideForecastCoverage() {
        Instant generatedAt = Instant.parse("2026-08-30T10:00:00Z");
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);
        LunarEclipseEventService eclipses = mock(LunarEclipseEventService.class);
        NearPerigeeFullMoonService fullMoons = mock(NearPerigeeFullMoonService.class);
        when(eclipses.discover(any(), any(), any(), any())).thenReturn(List.of(
                eclipse("far-eclipse", "2027-02-20T23:12:44.142Z")));
        when(fullMoons.discover(any(), any(), any(), any())).thenReturn(List.of(
                fullMoon("no-local", "2027-01-22T12:17:50.281Z", false)));

        Success response = (Success) service(
                fixed(generatedAt), resolved(PRAGUE), weather, eclipses, fullMoons)
                .search("prague-cz", OpportunityPreferences.none(), 18, List.of(), 0);

        assertThat(((LunarEclipseEvent) response.events().get(1)).weather().status())
                .isEqualTo("outside_forecast_horizon");
        verifyNoInteractions(weather);
    }

    @Test
    void appliesOneTemporaryWeatherFailureToCoveredEventsOnly() {
        Instant generatedAt = Instant.parse("2026-08-30T10:00:00Z");
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);
        when(weather.forecastFor(any(), any(), any()))
                .thenThrow(new WeatherForecastUnavailableException("temporary"));
        LunarEclipseEventService eclipses = mock(LunarEclipseEventService.class);
        NearPerigeeFullMoonService fullMoons = mock(NearPerigeeFullMoonService.class);
        when(eclipses.discover(any(), any(), any(), any())).thenReturn(List.of(
                eclipse("covered", "2026-09-01T02:30:00Z")));
        when(fullMoons.discover(any(), any(), any(), any())).thenReturn(List.of(
                fullMoon("far", "2027-01-22T12:17:50.281Z", true)));

        Success response = (Success) service(
                fixed(generatedAt), resolved(PRAGUE), weather, eclipses, fullMoons)
                .search("prague-cz", OpportunityPreferences.none(), 18, List.of(), 0);

        assertThat(((LunarEclipseEvent) response.events().getFirst()).weather().status())
                .isEqualTo("temporarily_unavailable");
        assertThat(((FullMoonEvent) response.events().get(1)).weather().status())
                .isEqualTo("outside_forecast_horizon");
        verify(weather, times(1)).forecastFor(any(), any(), any());
    }

    @Test
    void keepsLocationResolutionOutcomesDistinctWithoutDiscoveryOrWeather() {
        Instant generatedAt = Instant.parse("2026-08-30T10:00:00Z");
        LocationResolver resolver = mock(LocationResolver.class);
        when(resolver.resolveLocationId("missing")).thenReturn(LocationResolution.notFound());
        when(resolver.resolveLocationId("unavailable"))
                .thenReturn(LocationResolution.temporarilyUnavailable());
        when(resolver.resolveLocationId("ambiguous"))
                .thenReturn(LocationResolution.ambiguous(List.of(
                        PRAGUE,
                        new ResolvedLocation(
                                "other",
                                new ProviderLocationId(LocationProvider.OPEN_METEO, "other"),
                                "Other",
                                49.0,
                                15.0,
                                0,
                                ZoneId.of("Europe/Prague"),
                                "CZ"))));
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);
        LunarEclipseEventService eclipses = mock(LunarEclipseEventService.class);
        NearPerigeeFullMoonService fullMoons = mock(NearPerigeeFullMoonService.class);
        MoonEventService service = service(
                fixed(generatedAt), resolver, weather, eclipses, fullMoons);

        assertThat(service.search(
                "missing", OpportunityPreferences.none(), 18, List.of(), 0))
                .isEqualTo(new Status(
                        "location_not_found",
                        generatedAt.toString(),
                        "No matching location found."));
        assertThat(service.search(
                "unavailable", OpportunityPreferences.none(), 18, List.of(), 0))
                .isEqualTo(new Status(
                        "temporarily_unavailable",
                        generatedAt.toString(),
                        "Location lookup is temporarily unavailable."));
        assertThat(service.search(
                "ambiguous", OpportunityPreferences.none(), 18, List.of(), 0))
                .isInstanceOf(Candidates.class);
        verifyNoInteractions(weather, eclipses, fullMoons);
    }

    private static MoonEventService service(
            Clock clock,
            LocationResolver resolver,
            WeatherForecastProvider weather,
            LunarEclipseEventService eclipses,
            NearPerigeeFullMoonService fullMoons
    ) {
        return new MoonEventService(
                resolver,
                weather,
                new OpportunitySearchDefaults(clock),
                eclipses,
                fullMoons,
                clock);
    }

    private static LocationResolver resolved(ResolvedLocation location) {
        LocationResolver resolver = mock(LocationResolver.class);
        when(resolver.resolveLocationId(location.locationId()))
                .thenReturn(LocationResolution.resolved(location));
        return resolver;
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static LunarEclipseEvent eclipse(String id, String maximumAt) {
        Interval interval = new Interval(
                Instant.parse(maximumAt).minusSeconds(3_600).toString(),
                Instant.parse(maximumAt).plusSeconds(3_600).toString());
        return new LunarEclipseEvent(
                id,
                "lunar_eclipse",
                "total",
                interval.startsAt(),
                maximumAt,
                interval.endsAt(),
                100.0,
                List.of(new EclipsePhase(
                        "total",
                        interval.startsAt(),
                        interval.endsAt(),
                        new PhaseVisibility("fully_visible", List.of(interval)))),
                List.of(new EclipseShadowSample(
                        maximumAt,
                        new EclipseShadowMoon(20.0, 180.0, 12.0),
                        new EclipseShadow(-0.3, 0.4, 2.7, 4.7))),
                new MoonPosition(20.0, 180.0),
                new EventVisibility(
                        "fully_visible",
                        List.of(interval),
                        interval,
                        display(interval, maximumAt),
                        moonPath(interval, maximumAt,
                                new EclipseShadow(-0.3, 0.4, 2.7, 4.7))),
                new PreferenceAssessment("no_active_preferences", List.of()),
                Weather.outsideForecastHorizon());
    }

    private static FullMoonEvent fullMoon(
            String id,
            String peakAt,
            boolean local
    ) {
        LocalViewing viewing = null;
        Weather weather = null;
        if (local) {
            Interval interval = new Interval(
                    Instant.parse(peakAt).minusSeconds(3_600).toString(),
                    Instant.parse(peakAt).plusSeconds(3_600).toString());
            viewing = new LocalViewing(
                    List.of(interval), interval, display(interval, peakAt),
                    moonPath(interval, peakAt, null));
            weather = Weather.outsideForecastHorizon();
        }
        return new FullMoonEvent(
                id,
                "full_moon",
                peakAt,
                List.of(new FullMoonQualifier(
                        "near_perigee", 1, 0.95, 358_000, 357_000, 406_000)),
                viewing,
                new PreferenceAssessment("no_active_preferences", List.of()),
                weather);
    }

    private static DisplayInterval display(Interval interval, String suggestedAt) {
        return new DisplayInterval(
                interval.startsAt(),
                suggestedAt,
                interval.endsAt(),
                new MoonPosition(20.0, 180.0),
                new SunPosition(-15.0, "night"));
    }

    private static MoonPath moonPath(
            Interval interval,
            String suggestedAt,
            EclipseShadow shadow
    ) {
        return new MoonPath(List.of(
                        pathSample(interval.startsAt(), shadow),
                        pathSample(suggestedAt, shadow),
                        pathSample(interval.endsAt(), shadow)));
    }

    private static MoonPathSample pathSample(String at, EclipseShadow shadow) {
        return new MoonPathSample(
                at, 20.0, 180.0, 180.0, 0.0, 12.0,
                -15.0, 182.0, "night", shadow);
    }

    private static HourlyWeather weatherHour(String startsAt) {
        return new HourlyWeather(
                Instant.parse(startsAt), 38, 20, 25, 30, 5, 0.0, 20_000, 2, 1.0);
    }
}
