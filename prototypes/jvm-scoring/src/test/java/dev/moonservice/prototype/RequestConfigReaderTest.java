package dev.moonservice.prototype;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Instant;

import static com.fasterxml.jackson.databind.json.JsonMapper.builder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestConfigReaderTest {
    @Test
    void readsPraguePreviewRequestFixture() {
        PrototypeConfig config = RequestConfigReader.read(Path.of("fixtures/prague-preview-request.json"));

        assertEquals("prague-cz", config.location().slug());
        assertEquals(Instant.parse("2026-06-29T00:00:00Z"), config.start());
        assertEquals(7, config.days());
        assertEquals(30, config.stepMinutes());
        assertEquals(12.0, config.maxMoonAltitudeDegrees());
        assertEquals(50, config.minScore());
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
            {"locationId": ""} | locationId must be a non-empty string in the request fixture.
            {"start": "not-a-date"} | Invalid --start value: not-a-date
            {"forecastHorizonDays": 0} | forecastHorizonDays must be between 1 and 30.
            {"stepMinutes": 0} | stepMinutes must be between 1 and 180.
            {"limit": 0} | limit must be between 1 and 100.
            {"minScore": 101} | minScore must be between 0 and 100.
            {"maxMoonAltitudeDegrees": 46} | maxMoonAltitudeDegrees must be between 0.0 and 45.0.
            """)
    void rejectsInvalidRequestFixtures(String json, String message) throws Exception {
        UsageException ex = assertThrows(
                UsageException.class,
                () -> RequestConfigReader.fromJson(builder().build().readTree(json))
        );

        assertEquals(message, ex.getMessage());
    }
}
