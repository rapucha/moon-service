package dev.moonservice.scoringprototype;

import dev.moonservice.scoringprototype.cli.MoonScoringPrototype;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.input.RequestConfigReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static tools.jackson.databind.json.JsonMapper.builder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestConfigReaderTest {
    @Test
    void readsPraguePreviewRequestFixture() {
        PrototypeConfig config = RequestConfigReader.read(Path.of("fixtures/prague-preview-request.json"));

        assertEquals("prague-cz", config.location().slug());
        assertEquals(LocalDate.parse("2026-06-29"), config.startDate());
        assertEquals(Instant.parse("2026-06-28T22:00:00Z"), config.start());
        assertEquals(7, config.days());
        assertEquals(12.0, config.maxMoonAltitudeDegrees());
        assertEquals(5, config.limit());
    }

    @Test
    void cliRequestModeMustBeUsedByItself() {
        UsageException ex = assertThrows(
                UsageException.class,
                () -> MoonScoringPrototype.parseConfig(new String[] {
                        "--request",
                        "fixtures/prague-preview-request.json",
                        "--limit",
                        "3"
                })
        );

        assertEquals("--request must be used by itself in this prototype.", ex.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            [] | Request fixture must be a JSON object.
            {} | locationId is required in the request fixture.
            {"locationId": "prague-cz"} | start is required in the request fixture.
            {"locationId": "prague-cz", "start": "2026-06-29"} | forecastHorizonDays is required in the request fixture.
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7} | maxMoonAltitudeDegrees is required in the request fixture.
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 12} | limit is required in the request fixture.
            {"locationId": ""} | locationId must be a non-empty string in the request fixture.
            {"locationId": "prague-cz", "start": "not-a-date", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 12, "limit": 5} | Invalid --start value: not-a-date
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 0, "maxMoonAltitudeDegrees": 12, "limit": 5} | forecastHorizonDays must be between 1 and 30.
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 12, "limit": 0} | limit must be between 1 and 100.
            {"locationId": "prague-cz", "start": "2026-06-29", "forecastHorizonDays": 7, "maxMoonAltitudeDegrees": 91, "limit": 5} | maxMoonAltitudeDegrees must be between 0.0 and 90.0.
            """)
    void rejectsInvalidRequestFixtures(String json, String message) {
        UsageException ex = assertThrows(
                UsageException.class,
                () -> RequestConfigReader.fromJson(builder().build().readTree(json))
        );

        assertEquals(message, ex.getMessage());
    }
}
