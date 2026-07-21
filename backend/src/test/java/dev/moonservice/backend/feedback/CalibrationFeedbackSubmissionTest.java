package dev.moonservice.backend.feedback;

import dev.moonservice.backend.web.HostedAlphaSurfaceFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CalibrationFeedbackSubmissionTest {
    private static final String SUBMISSIONS = "/api/calibration-feedback/v1/submissions";
    private static final UUID CLIENT_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
    private static final UUID SERVER_ID = UUID.fromString("2c1b827c-981f-4d4f-98d5-89bbd62792dc");
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-07-20T10:15:30.123456789Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-20T10:15:30.123456Z");

    private CalibrationFeedbackService service;
    private CalibrationFeedbackController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CalibrationFeedbackService.class);
        controller = new CalibrationFeedbackController(
                service,
                Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC),
                new ObjectMapper(),
                "  test-revision  ");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void exposesStableNoStoreCapability() throws Exception {
        when(service.capability()).thenReturn(new CalibrationFeedbackService.Capability("enabled", "available"));

        mvc.perform(get("/api/calibration-feedback/v1/capability"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.serverTime").value("2026-07-20T10:15:30.123456Z"))
                .andExpect(jsonPath("$.featureState").value("enabled"))
                .andExpect(jsonPath("$.submissionAvailability").value("available"));
    }

    @Test
    void hostedSurfaceAllowsTheContractRoutesWithoutCrossOriginPreflight() throws Exception {
        HostedAlphaSurfaceFilter filter = new HostedAlphaSurfaceFilter(true);

        MockHttpServletResponse capability = surfaceExchange(
                filter, new MockHttpServletRequest("GET", "/api/calibration-feedback/v1/capability"));
        assertThat(capability.getStatus()).isEqualTo(200);
        assertThat(capability.getHeader("Cache-Control")).isEqualTo("no-store");

        MockHttpServletRequest submission = new MockHttpServletRequest("POST", SUBMISSIONS);
        submission.addHeader("Origin", "https://example.invalid");
        submission.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse submitted = surfaceExchange(filter, submission);
        assertThat(submitted.getStatus()).isEqualTo(200);
        assertThat(submitted.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(submitted.getHeader("Access-Control-Allow-Origin")).isNull();

        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", SUBMISSIONS);
        preflight.addHeader("Origin", "https://example.invalid");
        preflight.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse rejected = surfaceExchange(filter, preflight);
        assertThat(rejected.getStatus()).isEqualTo(405);
        assertThat(rejected.getHeader("Allow")).isEqualTo("POST");
        assertThat(rejected.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(rejected.getContentAsByteArray()).isEmpty();
    }

    @Test
    void acceptsUtf8JsonOnlyAfterTheWholeBoundedBodyAndReturnsCreatedShape() throws Exception {
        when(service.submit(any(), eq(RECEIVED_AT), eq("test-revision")))
                .thenReturn(new CalibrationFeedbackService.Created(CLIENT_ID, SERVER_ID, RECEIVED_AT));

        mvc.perform(post(SUBMISSIONS)
                        .contentType("application/json; charset=utf-8")
                        .content(validJson("\"ambientLight\":\"good\"," +
                                "\"notes\":\"\\u00a0Cafe\\u0301 crescent\\u3000\"")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.serverTime").value("2026-07-20T10:15:30.123456Z"))
                .andExpect(jsonPath("$.status").value("created"))
                .andExpect(jsonPath("$.clientSubmissionId").value(CLIENT_ID.toString()))
                .andExpect(jsonPath("$.serverReportId").value(SERVER_ID.toString()))
                .andExpect(jsonPath("$.submittedAt").value("2026-07-20T10:15:30.123456Z"));

        ArgumentCaptor<CalibrationFeedbackSubmission> submission =
                ArgumentCaptor.forClass(CalibrationFeedbackSubmission.class);
        verify(service).submit(submission.capture(), eq(RECEIVED_AT), eq("test-revision"));
        assertThat(submission.getValue().locationId()).isEqualTo("moon-service-3067696");
        assertThat(submission.getValue().opportunityId()).isEqualTo("opportunity-1");
        assertThat(submission.getValue().notes()).isEqualTo("Café crescent");
    }

    @Test
    void returnsOriginalIdentifiersAndTimeForReplay() throws Exception {
        Instant originalTime = Instant.parse("2026-07-19T08:00:00.000001Z");
        when(service.submit(any(), eq(RECEIVED_AT), eq("test-revision")))
                .thenReturn(new CalibrationFeedbackService.Replayed(CLIENT_ID, SERVER_ID, originalTime));

        mvc.perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content(validJson(
                        "\"crescentVisibility\":\"visible\"")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("replayed"))
                .andExpect(jsonPath("$.serverTime").value("2026-07-20T10:15:30.123456Z"))
                .andExpect(jsonPath("$.submittedAt").value("2026-07-19T08:00:00.000001Z"));
    }

    @ParameterizedTest
    @MethodSource("serviceErrors")
    void mapsStableServiceErrors(
            CalibrationFeedbackService.SubmissionResult result,
            int expectedStatus,
            String expectedCode,
            String expectedField
    ) throws Exception {
        when(service.submit(any(), eq(RECEIVED_AT), eq("test-revision"))).thenReturn(result);

        var response = mvc.perform(post(SUBMISSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson("\"ambientLight\":\"too_dark\"")))
                .andExpect(status().is(expectedStatus))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.error.code").value(expectedCode))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
        if (expectedField == null) {
            response.andExpect(jsonPath("$.error.field").doesNotExist());
        } else {
            response.andExpect(jsonPath("$.error.field").value(expectedField));
        }
    }

    static Stream<Arguments> serviceErrors() {
        return Stream.of(
                Arguments.of(new CalibrationFeedbackService.Conflict(), 409,
                        "client_submission_conflict", null),
                Arguments.of(new CalibrationFeedbackService.LocationNotFound(), 422,
                        "location_not_found", "locationId"),
                Arguments.of(new CalibrationFeedbackService.Unavailable(), 503,
                        "feedback_unavailable", null));
    }

    @Test
    void returnsMatchingRetryAfterHeaderAndBody() throws Exception {
        when(service.submit(any(), eq(RECEIVED_AT), eq("test-revision")))
                .thenReturn(new CalibrationFeedbackService.RateLimited(47));

        mvc.perform(post(SUBMISSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson("\"ambientLight\":\"good\"")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "47"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("rate_limited"))
                .andExpect(jsonPath("$.error.retryAfterSeconds").value(47));
    }

    @Test
    void rejectsUnsupportedTransportBeforeCallingTheService() throws Exception {
        mvc.perform(post(SUBMISSIONS).contentType(MediaType.TEXT_PLAIN).content(validJson("\"notes\":\"ok\"")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("unsupported_media_type"));

        mvc.perform(post(SUBMISSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Encoding", "gzip")
                        .content(validJson("\"notes\":\"ok\"")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("unsupported_media_type"));

        mvc.perform(post(SUBMISSIONS)
                        .contentType("application/json; charset=iso-8859-1")
                        .content(validJson("\"notes\":\"ok\"")))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(service);
    }

    @Test
    void enforcesTheStreamedByteLimitBeforeParsing() {
        MockHttpServletRequest request = streamedRequest(new byte[16_385]);

        ResponseEntity<?> response = controller.submit(request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        verifyNoInteractions(service);
    }

    @Test
    void acceptsAnExactlyBoundedBody() {
        when(service.submit(any(), eq(RECEIVED_AT), eq("test-revision")))
                .thenReturn(new CalibrationFeedbackService.Created(CLIENT_ID, SERVER_ID, RECEIVED_AT));
        byte[] json = validJson("\"notes\":\"ok\"").getBytes(StandardCharsets.UTF_8);
        byte[] bounded = Arrays.copyOf(json, 16_384);
        Arrays.fill(bounded, json.length, bounded.length, (byte) ' ');

        ResponseEntity<?> response = controller.submit(streamedRequest(bounded));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(service).submit(any(), eq(RECEIVED_AT), eq("test-revision"));
    }

    @Test
    void rejectsMalformedJsonUtf8DuplicatesAndClosedSchemaWithoutEchoingValues() throws Exception {
        mvc.perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content(new byte[]{(byte) 0xc3, 0x28}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_json"));

        mvc.perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_json"));

        String duplicate = validJson("\"notes\":\"first\",\"notes\":\"second\"");
        mvc.perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_json"));

        String unknown = validJson("\"notes\":\"ok\",\"secret\":\"do-not-echo-this\"");
        mvc.perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content(unknown))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("do-not-echo-this"))));

        verify(service, never()).submit(any(), any(), any());
    }

    @Test
    void distinguishesInvalidRequestFromInvalidReport() throws Exception {
        mvc.perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON)
                        .content(validJson("\"ambientLight\":null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(jsonPath("$.error.field").value("ambientLight"));

        mvc.perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON)
                        .content(validJson("\"notes\":\"\\u00a0\\u3000\"")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("invalid_report"))
                .andExpect(jsonPath("$.error.field").value("notes"));

        mvc.perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content(validJson("")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("invalid_report"));
    }

    @Test
    void implementsTheGoldenFramingAndDigestVector() throws Exception {
        CalibrationFeedbackSubmission submission = parse(validJson(
                "\"ambientLight\":\"good\",\"notes\":\"Nice crescent\""));

        byte[] framed = CalibrationFeedbackDigest.frameInput(
                "moon-service-3067696", "opportunity-1", "good", null, "Nice crescent");

        assertThat(framed).hasSize(119);
        assertThat(HexFormat.of().formatHex(submission.idempotencyHash()))
                .isEqualTo("cae49e707f8369f022638bcb97c365b6531e9c609bd312b920addc8cfeebd6d5");
    }

    @Test
    void hashesNormalizedSemanticValuesNotJsonSpelling() throws Exception {
        CalibrationFeedbackSubmission decomposed = parse("""
                { "notes":"\\u00a0Cafe\\u0301 crescent\\u3000", "ambientLight":"good",
                  "opportunityId":"opportunity-\\u0031", "locationId":"\\tmoon-service-3067696\\u00a0",
                  "clientSubmissionId":"f47ac10b-58cc-4372-a567-0e02b2c3d479", "schemaVersion":1 }
                """);
        CalibrationFeedbackSubmission composed = parse(validJson(
                "\"ambientLight\":\"good\",\"notes\":\"Café crescent\""));
        CalibrationFeedbackSubmission changedSlot = parse(validJson(
                "\"ambientLight\":\"good\",\"crescentVisibility\":\"visible\"," +
                        "\"notes\":\"Café crescent\""));
        CalibrationFeedbackSubmission changedContent = parse(validJson(
                "\"ambientLight\":\"good\",\"notes\":\"Different note\""));

        assertThat(decomposed.locationId()).isEqualTo("moon-service-3067696");
        assertThat(decomposed.opportunityId()).isEqualTo("opportunity-1");
        assertThat(decomposed.notes()).isEqualTo("Café crescent");
        assertArrayEquals(composed.idempotencyHash(), decomposed.idempotencyHash());
        assertThat(changedSlot.idempotencyHash()).isNotEqualTo(composed.idempotencyHash());
        assertThat(changedContent.idempotencyHash()).isNotEqualTo(composed.idempotencyHash());

        byte[] exposed = composed.idempotencyHash();
        exposed[0] ^= 0xff;
        assertThat(composed.idempotencyHash()[0]).isNotEqualTo(exposed[0]);
    }

    @Test
    void rejectsNonCanonicalIdentifiersAndMalformedUnicode() {
        assertInvalid(validJson("\"notes\":\"ok\"")
                .replace(CLIENT_ID.toString(), CLIENT_ID.toString().toUpperCase()), "invalid_request");
        assertInvalid(validJson("\"notes\":\"ok\"")
                .replace("opportunity-1", " opportunity-1"), "invalid_request");
        assertInvalid(validJson("\"notes\":\"\\ud800\""), "invalid_request");
    }

    @Test
    void enforcesSchemaEnumIdentifierAndNoteBounds() throws Exception {
        assertInvalid(validJson("\"notes\":\"ok\"")
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "invalid_request");
        assertInvalid(validJson("\"ambientLight\":\"unknown\""), "invalid_request");
        assertInvalid(validJson("\"notes\":\"ok\"")
                .replace("moon-service-3067696", "a".repeat(101)), "invalid_request");
        assertInvalid(validJson("\"notes\":\"ok\"")
                .replace("moon-service-3067696", "moon\\tservice"), "invalid_request");
        assertInvalid(validJson("\"notes\":\"ok\"")
                .replace("opportunity-1", "opportunity-\\u202e1"), "invalid_request");
        assertInvalid(validJson("\"notes\":\"\\u0000\""), "invalid_request");
        assertInvalid(validJson("\"notes\":\"" + "x".repeat(4_001) + "\""), "invalid_request");

        assertThat(parse(validJson("\"notes\":\"" + "x".repeat(4_000) + "\"")).notes())
                .hasSize(4_000);
    }

    private static CalibrationFeedbackSubmission parse(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper().rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return CalibrationFeedbackSubmission.parse(mapper, json);
    }

    private static void assertInvalid(String json, String code) {
        CalibrationFeedbackSubmission.Invalid invalid = assertThrows(
                CalibrationFeedbackSubmission.Invalid.class, () -> parse(json));
        assertThat(invalid.code).isEqualTo(code);
    }

    private static String validJson(String evidenceMembers) {
        String separator = evidenceMembers.isEmpty() ? "" : "," + evidenceMembers;
        return "{\"schemaVersion\":1,"
                + "\"clientSubmissionId\":\"" + CLIENT_ID + "\","
                + "\"locationId\":\"\\u00a0moon-service-3067696\\u3000\","
                + "\"opportunityId\":\"opportunity-1\"" + separator + "}";
    }

    private static MockHttpServletRequest streamedRequest(byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SUBMISSIONS) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(content);
        return request;
    }

    private static MockHttpServletResponse surfaceExchange(
            HostedAlphaSurfaceFilter filter,
            MockHttpServletRequest request
    ) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (ignoredRequest, finalResponse) -> ((MockHttpServletResponse) finalResponse).setStatus(200));
        return response;
    }
}
