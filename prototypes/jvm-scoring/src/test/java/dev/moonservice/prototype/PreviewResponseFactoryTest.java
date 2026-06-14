package dev.moonservice.prototype;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewResponseFactoryTest {
    @Test
    void buildsTypedResponseForPragueFixture() {
        PrototypeConfig config = RequestConfigReader.read(Path.of("fixtures/prague-preview-request.json"));
        PrototypeResult result = new OpportunityService().evaluate(config);

        PreviewResponse response = new PreviewResponseFactory().from(result);

        assertEquals("ok", response.status());
        assertEquals("openmeteo:prague-cz", response.location().id());
        assertEquals(7, response.forecastHorizonDays());
        assertEquals(337, response.samplesEvaluated());
        assertEquals("prague-cz-2026-07-01T0300Z", response.opportunities().getFirst().id());
        assertEquals(99, response.opportunities().getFirst().score());
        assertEquals("golden_hour", response.opportunities().getFirst().sun().lightBucket());
        assertEquals(
                "moon_detail_easy_foreground_supported",
                response.opportunities().getFirst().exposureBalance().label()
        );
        assertEquals("fixed_fixture", response.diagnostics().weatherSource());
    }
}
