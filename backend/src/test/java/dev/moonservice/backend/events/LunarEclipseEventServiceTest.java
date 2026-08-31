package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.*;
import dev.moonservice.backend.location.*;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.weather.*;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.*;
import dev.moonservice.scoringprototype.window.RefinedTimeGrid;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LunarEclipseEventServiceTest {
    private static final ResolvedLocation PRAGUE = new TestOpenMeteoLocationResolver()
            .resolveLocationId("prague-cz").singleCandidate().orElseThrow();

    @Test
    void returnsVisibleTotalPartialAndPenumbralEclipsesWithObjectiveFactsAndOneWeatherLookup() {
        Clock clock = fixed("2025-09-01T00:00:00Z");
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);
        HourlyWeather hour = weatherHour("2025-09-07T18:00:00Z");
        when(weather.forecastFor(any(), any(), any()))
                .thenReturn(new HourlyWeatherForecast(List.of(hour)));

        Success response = success(service(clock, PRAGUE, weather).search(
                "prague-cz", OpportunityPreferences.none(), List.of(), 0));

        assertThat(response.generatedAt()).isEqualTo("2025-09-01T00:00:00Z");
        assertThat(response.endsAt()).isEqualTo("2027-03-01T01:00:00Z");
        assertThat(response.location()).isEqualTo(new MoonEventResponse.Location(
                "prague-cz", "real_location", "Prague, Czechia", "Europe/Prague", "CZ"));
        assertThat(response.events()).extracting(LunarEclipseEvent::subtype)
                .containsExactly("total", "partial", "penumbral");
        assertThat(response.events()).extracting(LunarEclipseEvent::maximumAt)
                .containsExactly(
                        "2025-09-07T18:11:41.502Z",
                        "2026-08-28T04:12:49.077Z",
                        "2027-02-20T23:12:44.142Z");
        assertThat(response.events()).noneMatch(event ->
                event.maximumAt().equals("2026-03-03T11:33:40.289Z"));

        LunarEclipseEvent total = response.events().getFirst();
        assertThat(total.kind()).isEqualTo("lunar_eclipse");
        assertThat(total.startsAt()).isEqualTo("2025-09-07T15:28:02.516Z");
        assertThat(total.endsAt()).isEqualTo("2025-09-07T20:55:20.487Z");
        assertThat(total.phases()).extracting(EclipsePhase::kind)
                .containsExactly("penumbral", "partial", "total");
        assertThat(total.phases().get(1).startsAt()).isEqualTo("2025-09-07T16:26:40.111Z");
        assertThat(total.phases().get(2).endsAt()).isEqualTo("2025-09-07T18:53:08.096Z");
        assertThat(total.localVisibility().displayInterval().suggestedAt())
                .isEqualTo(total.maximumAt());
        assertThat(total.shadowSamples()).extracting(EclipseShadowSample::at)
                .containsExactly(
                        "2025-09-07T15:28:02.516Z",
                        "2025-09-07T16:26:40.111Z",
                        "2025-09-07T17:30:14.908Z",
                        "2025-09-07T18:11:41.502Z",
                        "2025-09-07T18:53:08.096Z",
                        "2025-09-07T19:56:42.892Z",
                        "2025-09-07T20:55:20.487Z");
        EclipseShadowSample maximumShadow = total.shadowSamples().get(3);
        assertThat(maximumShadow.moon().northPoleTiltDegrees()).isNotNull();
        assertThat(maximumShadow.shadow().centerRightMoonRadii())
                .isCloseTo(-0.1673, within(0.0001));
        assertThat(maximumShadow.shadow().centerUpMoonRadii())
                .isCloseTo(0.9953, within(0.0001));
        assertThat(maximumShadow.shadow().penumbraRadiusMoonRadii())
                .isGreaterThan(maximumShadow.shadow().umbraRadiusMoonRadii());
        assertThat(total.moonAtMaximum().altitudeDegrees()).isCloseTo(5.730577, within(0.00001));
        assertThat(total.moonAtMaximum().azimuthDegrees()).isCloseTo(107.552074, within(0.00001));
        assertThat(total.preferenceAssessment().overall()).isEqualTo("no_active_preferences");
        assertThat(total.preferenceAssessment().filters()).isEmpty();
        assertThat(total.weather()).isEqualTo(new Weather(
                "available", hour.startsAt().toString(), "partly cloudy", 38, 5));

        LunarEclipseEvent partial = response.events().get(1);
        assertThat(partial.umbralObscurationPercent()).isCloseTo(96.6055593, within(0.000001));
        assertThat(partial.phases()).extracting(EclipsePhase::kind)
                .containsExactly("penumbral", "partial");
        assertThat(partial.shadowSamples()).hasSize(5);
        assertThat(partial.localVisibility().status()).isEqualTo("partly_visible");
        assertThat(partial.weather().status()).isEqualTo("outside_forecast_horizon");

        LunarEclipseEvent penumbral = response.events().get(2);
        assertThat(penumbral.umbralObscurationPercent()).isZero();
        assertThat(penumbral.phases()).extracting(EclipsePhase::kind).containsExactly("penumbral");
        assertThat(penumbral.shadowSamples()).hasSize(3);
        assertThat(penumbral.localVisibility().status()).isEqualTo("fully_visible");
        assertThat(penumbral.weather().status()).isEqualTo("outside_forecast_horizon");

        var location = org.mockito.ArgumentCaptor.forClass(ResolvedLocation.class);
        var startsAt = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var endsAt = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(weather).forecastFor(location.capture(), startsAt.capture(), endsAt.capture());
        assertThat(location.getValue()).isEqualTo(PRAGUE);
        assertThat(startsAt.getValue()).isEqualTo(Instant.parse("2025-08-31T22:00:00Z"));
        assertThat(endsAt.getValue()).isEqualTo(Instant.parse("2025-09-07T22:00:00Z"));
    }

    @Test
    void returnsEmptyAndSkipsWeatherWhenNoVisibleEclipseExists() {
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);

        Success response = success(service(fixed("2014-01-01T00:00:00Z"), PRAGUE, weather).search(
                "prague-cz", OpportunityPreferences.none(), List.of("/unknown"), 3));

        assertThat(response.endsAt()).isEqualTo("2015-06-30T23:00:00Z");
        assertThat(response.events()).isEmpty();
        assertThat(response.ignoredPreferenceFields()).containsExactly("/unknown");
        assertThat(response.ignoredPreferenceFieldCount()).isEqualTo(3);
        assertThat(response.additionalIgnoredPreferenceFieldCount()).isEqualTo(2);
        verifyNoInteractions(weather);
    }

    @Test
    void includesAnAlreadyRunningEclipseAndClampsOnlyItsDisplayInterval() {
        Clock clock = fixed("2026-08-28T04:13:00Z");
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);
        when(weather.forecastFor(any(), any(), any())).thenReturn(
                new HourlyWeatherForecast(List.of(weatherHour("2026-08-28T04:00:00Z"))));

        LunarEclipseEvent event = eventAt(success(service(clock, PRAGUE, weather).search(
                "prague-cz", OpportunityPreferences.none(), List.of(), 0)),
                "2026-08-28T04:12:49.077Z");

        assertThat(event.startsAt()).isEqualTo("2026-08-28T01:23:36.003Z");
        assertThat(event.endsAt()).isEqualTo("2026-08-28T07:02:02.151Z");
        assertThat(event.localVisibility().selectedInterval().startsAt())
                .isEqualTo("2026-08-28T01:23:36.003Z");
        assertThat(event.localVisibility().selectedInterval().endsAt())
                .isBetween("2026-08-28T04:15:25Z", "2026-08-28T04:15:27Z");
        assertThat(event.localVisibility().displayInterval().startsAt())
                .isEqualTo("2026-08-28T04:13:00Z");
        assertThat(event.localVisibility().displayInterval().suggestedAt())
                .isEqualTo("2026-08-28T04:13:00Z");
        assertThat(event.localVisibility().displayInterval().endsAt())
                .isEqualTo(event.localVisibility().selectedInterval().endsAt());
        assertThat(event.shadowSamples()).hasSize(6);
        assertThat(event.shadowSamples()).extracting(EclipseShadowSample::at)
                .contains("2026-08-28T04:13:00Z");
    }

    @Test
    void keepsTheHorizonEndExclusiveForSuggestions() {
        Instant maximum = Instant.parse("2027-02-20T23:12:44.142Z");
        Instant generatedAt = maximum.atZone(PRAGUE.zoneId()).minusMonths(18).toInstant();
        Success response = success(service(
                Clock.fixed(generatedAt, ZoneOffset.UTC), PRAGUE, mock(WeatherForecastProvider.class)).search(
                "prague-cz", OpportunityPreferences.none(), List.of(), 0));
        LunarEclipseEvent eclipse = eventAt(response, maximum.toString());

        assertThat(response.endsAt()).isEqualTo(maximum.toString());
        assertThat(Instant.parse(eclipse.localVisibility().displayInterval().suggestedAt()))
                .isEqualTo(maximum.minusSeconds(1));
    }

    @Test
    void representsNotVisiblePhasesAndSelectsOneOfMultipleActualIntervals() {
        Success settingResponse = success(service(
                fixed("2021-11-19T06:00:00Z"), PRAGUE, unavailableWeather()).search(
                "prague-cz", OpportunityPreferences.none(), List.of(), 0));
        assertThat(settingResponse.events()).extracting(LunarEclipseEvent::maximumAt)
                .contains("2021-11-19T09:02:55.259Z");
        LunarEclipseEvent settingEclipse = eventAt(settingResponse,
                "2021-11-19T09:02:55.259Z");
        EclipsePhase partial = settingEclipse.phases().stream()
                .filter(phase -> phase.kind().equals("partial"))
                .findFirst().orElseThrow();
        assertThat(partial.localVisibility().status()).isEqualTo("not_visible");
        assertThat(partial.localVisibility().intervals()).isEmpty();

        ResolvedLocation polar = location(
                "polar-test", 80.5, -165.57924, ZoneOffset.UTC);
        LunarEclipseEvent split = eventAt(success(service(
                fixed("2027-02-20T20:00:00Z"), polar, unavailableWeather()).search(
                "polar-test", OpportunityPreferences.none(), List.of(), 0)),
                "2027-02-20T23:12:44.142Z");
        assertThat(split.localVisibility().intervals()).hasSize(2);
        assertThat(split.localVisibility().selectedInterval())
                .isEqualTo(split.localVisibility().intervals().getFirst());

        RefinedTimeGrid.Interval earlier = new RefinedTimeGrid.Interval(
                Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-01-01T01:00:00Z"));
        RefinedTimeGrid.Interval later = new RefinedTimeGrid.Interval(
                Instant.parse("2027-01-01T03:00:00Z"), Instant.parse("2027-01-01T04:00:00Z"));
        assertThat(LunarEclipseEventService.select(
                List.of(later, earlier), Instant.parse("2027-01-01T02:00:00Z")))
                .isEqualTo(earlier);
    }

    @Test
    void assessesOnlyAltitudeAndAzimuthWithoutChangingTheEvent() {
        LunarEclipseEvent allOff = firstEvent(OpportunityPreferences.none());
        assertThat(allOff.preferenceAssessment().overall()).isEqualTo("no_active_preferences");
        assertThat(allOff.preferenceAssessment().filters()).isEmpty();
        assertThat(allOff.localVisibility().displayInterval().suggestedAt())
                .isEqualTo(allOff.maximumAt());

        OpportunityPreferences everyFilter = new OpportunityPreferences(
                1,
                new AltitudeRange(5, 6),
                new AzimuthPreference(new DegreeRange(107, 108), null),
                new TimePreference(TimeMode.LOCAL_CLOCK,
                        new LocalClockWindow(LocalTime.of(0, 0), LocalTime.of(1, 0)), null),
                Set.of(NamedPhase.NEW_MOON),
                List.of(new DegreeRange(0, 1)));
        LunarEclipseEvent matching = firstEvent(everyFilter);
        assertThat(matching.preferenceAssessment().overall()).isEqualTo("matches");
        assertThat(matching.preferenceAssessment().filters())
                .containsExactly(
                        new FilterAssessment("altitudeDegrees", "matches"),
                        new FilterAssessment("azimuthDegrees", "matches"));

        OpportunityPreferences irrelevantOnly = new OpportunityPreferences(
                1,
                null,
                null,
                new TimePreference(TimeMode.LIGHT_BUCKET, null, Set.of(AmbientLight.DAYLIGHT)),
                Set.of(NamedPhase.NEW_MOON),
                List.of(new DegreeRange(0, 1)));
        LunarEclipseEvent irrelevant = firstEvent(irrelevantOnly);
        assertThat(irrelevant.preferenceAssessment().overall()).isEqualTo("no_active_preferences");
        assertThat(irrelevant.preferenceAssessment().filters()).isEmpty();

        OpportunityPreferences wrongAltitude = new OpportunityPreferences(
                1,
                new AltitudeRange(20, 30),
                new AzimuthPreference(new DegreeRange(107, 108), null),
                null,
                null,
                null);
        LunarEclipseEvent mismatch = firstEvent(wrongAltitude);
        assertThat(mismatch.preferenceAssessment().overall()).isEqualTo("does_not_match");
        assertThat(mismatch.preferenceAssessment().filters())
                .containsExactly(
                        new FilterAssessment("altitudeDegrees", "does_not_match"),
                        new FilterAssessment("azimuthDegrees", "matches"));

        assertThat(List.of(matching, irrelevant, mismatch))
                .allSatisfy(event -> {
                    assertThat(event.id()).isEqualTo(allOff.id());
                    assertThat(event.startsAt()).isEqualTo(allOff.startsAt());
                    assertThat(event.maximumAt()).isEqualTo(allOff.maximumAt());
                    assertThat(event.endsAt()).isEqualTo(allOff.endsAt());
                    assertThat(event.localVisibility()).isEqualTo(allOff.localVisibility());
                    assertThat(event.shadowSamples()).isEqualTo(allOff.shadowSamples());
                });
    }

    @Test
    void weatherFailureOrMissingHourDoesNotRemoveTheAstronomicalEvent() {
        WeatherForecastProvider failed = mock(WeatherForecastProvider.class);
        when(failed.forecastFor(any(), any(), any()))
                .thenThrow(new WeatherForecastUnavailableException("temporary"));
        Success failureResponse = success(service(
                fixed("2025-09-01T00:00:00Z"), PRAGUE, failed).search(
                "prague-cz", OpportunityPreferences.none(), List.of(), 0));
        assertThat(failureResponse.events()).hasSize(3);
        assertThat(failureResponse.events().getFirst().weather().status())
                .isEqualTo("temporarily_unavailable");

        WeatherForecastProvider missing = mock(WeatherForecastProvider.class);
        when(missing.forecastFor(any(), any(), any())).thenReturn(
                new HourlyWeatherForecast(List.of(weatherHour("2025-09-07T17:00:00Z"))));
        Success missingResponse = success(service(
                fixed("2025-09-01T00:00:00Z"), PRAGUE, missing).search(
                "prague-cz", OpportunityPreferences.none(), List.of(), 0));
        assertThat(missingResponse.events()).hasSize(3);
        assertThat(missingResponse.events().getFirst().weather().status())
                .isEqualTo("temporarily_unavailable");

        WeatherForecastProvider unnecessary = mock(WeatherForecastProvider.class);
        Success outsideResponse = success(service(
                fixed("2025-09-08T00:00:00Z"), PRAGUE, unnecessary).search(
                "prague-cz", OpportunityPreferences.none(), List.of(), 0));
        assertThat(outsideResponse.events()).isNotEmpty();
        assertThat(outsideResponse.events()).allMatch(event ->
                event.weather().status().equals("outside_forecast_horizon"));
        verifyNoInteractions(unnecessary);
    }

    @Test
    void keepsLocationResolutionOutcomesDistinct() {
        Clock clock = fixed("2025-09-01T00:00:00Z");
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);
        LocationResolver resolver = mock(LocationResolver.class);
        when(resolver.resolveLocationId("missing")).thenReturn(LocationResolution.notFound());
        when(resolver.resolveLocationId("unavailable"))
                .thenReturn(LocationResolution.temporarilyUnavailable());
        when(resolver.resolveLocationId("ambiguous")).thenReturn(LocationResolution.ambiguous(List.of(
                PRAGUE, location("other", 49, 15, ZoneId.of("Europe/Prague")))));
        LunarEclipseEventService service = new LunarEclipseEventService(
                resolver, weather, new OpportunitySearchDefaults(clock), clock);

        assertThat(service.search("missing", OpportunityPreferences.none(), List.of(), 0))
                .isEqualTo(new Status(
                        "location_not_found", "2025-09-01T00:00:00Z", "No matching location found."));
        assertThat(service.search("unavailable", OpportunityPreferences.none(), List.of(), 0))
                .isEqualTo(new Status(
                        "temporarily_unavailable", "2025-09-01T00:00:00Z",
                        "Location lookup is temporarily unavailable."));
        assertThat(service.search("ambiguous", OpportunityPreferences.none(), List.of(), 0))
                .isInstanceOf(Candidates.class);
        verifyNoInteractions(weather);
    }

    private static LunarEclipseEvent firstEvent(OpportunityPreferences preferences) {
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);
        when(weather.forecastFor(any(), any(), any())).thenReturn(
                new HourlyWeatherForecast(List.of(weatherHour("2025-09-07T18:00:00Z"))));
        return success(service(fixed("2025-09-01T00:00:00Z"), PRAGUE, weather).search(
                "prague-cz", preferences, List.of(), 0)).events().getFirst();
    }

    private static LunarEclipseEvent eventAt(Success response, String maximumAt) {
        return response.events().stream()
                .filter(event -> event.maximumAt().equals(maximumAt))
                .findFirst().orElseThrow();
    }

    private static Success success(MoonEventResponse response) {
        return (Success) response;
    }

    private static LunarEclipseEventService service(
            Clock clock,
            ResolvedLocation location,
            WeatherForecastProvider weather
    ) {
        LocationResolver resolver = mock(LocationResolver.class);
        when(resolver.resolveLocationId(location.locationId()))
                .thenReturn(LocationResolution.resolved(location));
        return new LunarEclipseEventService(
                resolver, weather, new OpportunitySearchDefaults(clock), clock);
    }

    private static Clock fixed(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static ResolvedLocation location(
            String id,
            double latitude,
            double longitude,
            ZoneId zoneId
    ) {
        return new ResolvedLocation(
                id,
                new ProviderLocationId(LocationProvider.OPEN_METEO, id),
                id,
                latitude,
                longitude,
                0,
                zoneId,
                "XX");
    }

    private static HourlyWeather weatherHour(String startsAt) {
        return new HourlyWeather(
                Instant.parse(startsAt),
                38,
                20,
                25,
                30,
                5,
                0.0,
                20_000,
                2,
                1.0);
    }

    private static WeatherForecastProvider unavailableWeather() {
        WeatherForecastProvider weather = mock(WeatherForecastProvider.class);
        when(weather.forecastFor(any(), any(), any()))
                .thenThrow(new WeatherForecastUnavailableException("temporary"));
        return weather;
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
