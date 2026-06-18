package dev.moonservice.springpreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PreviewControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsPreviewResponseForPragueFixtureRequest() throws Exception {
        mockMvc.perform(post("/api/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "prague-cz",
                                  "start": "2026-06-29",
                                  "forecastHorizonDays": 7,
                                  "maxMoonAltitudeDegrees": 12,
                                  "limit": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.location.id").value("openmeteo:prague-cz"))
                .andExpect(jsonPath("$.forecastHorizonDays").value(7))
                .andExpect(jsonPath("$.candidateWindowsEvaluated").isNumber())
                .andExpect(jsonPath("$.opportunities[0].suggestedAt").exists())
                .andExpect(jsonPath("$.opportunities[0].weather.sourceResolution").value("hourly"))
                .andExpect(jsonPath("$.opportunities[0].links.ics", startsWith("/o/prague-cz-")));
    }

    @Test
    void mapsUnsupportedFixtureLocationToInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "amsterdam-nl",
                                  "start": "2026-06-29"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("Unsupported location for this prototype: amsterdam-nl"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            [] | Request fixture must be a JSON object.
            {"locationId": ""} | locationId must be a non-empty string in the request fixture.
            {"start": "not-a-date"} | Invalid --start value: not-a-date
            {"forecastHorizonDays": 0} | forecastHorizonDays must be between 1 and 30.
            {"limit": 0} | limit must be between 1 and 100.
            {"maxMoonAltitudeDegrees": 46} | maxMoonAltitudeDegrees must be between 0.0 and 45.0.
            """)
    void mapsInvalidRequestBodiesToInvalidRequest(String body, String message) throws Exception {
        mockMvc.perform(post("/api/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(message));
    }

    @Test
    void mapsMalformedJsonToInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("Request body must be valid JSON."));
    }
}
