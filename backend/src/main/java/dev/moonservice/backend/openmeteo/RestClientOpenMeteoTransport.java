package dev.moonservice.backend.openmeteo;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

public final class RestClientOpenMeteoTransport implements OpenMeteoTransport {
    private static final String USER_AGENT = "moon-service-backend/0.1";

    private final RestClient restClient;

    public RestClientOpenMeteoTransport(RestClient.Builder restClientBuilder, Duration timeout) {
        this.restClient = restClientBuilder.clone()
                .requestFactory(requestFactory(timeout))
                .build();
    }

    @Override
    public String get(URI requestUri) throws OpenMeteoTransportException {
        try {
            return restClient.get()
                    .uri(requestUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .exchange((request, response) -> responseBody(response));
        } catch (ResourceAccessException ex) {
            throw accessFailure(ex);
        } catch (RestClientException ex) {
            throw OpenMeteoTransportException.ioFailure(ex);
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private static String responseBody(ClientHttpResponse response) throws java.io.IOException {
        if (response.getStatusCode().is2xxSuccessful()) {
            return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        }
        throw httpFailure(response.getStatusCode().value(), response.getHeaders());
    }

    private static OpenMeteoTransportException httpFailure(int statusCode, HttpHeaders headers) {
        Optional<Duration> retryAfter = retryAfter(headers);
        if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return OpenMeteoTransportException.rateLimited(statusCode, retryAfter);
        }
        if (statusCode == HttpStatus.BAD_GATEWAY.value()
                || statusCode == HttpStatus.SERVICE_UNAVAILABLE.value()
                || statusCode == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return OpenMeteoTransportException.transientHttp(statusCode, retryAfter);
        }
        return OpenMeteoTransportException.nonRetryableHttp(statusCode, retryAfter);
    }

    private static Optional<Duration> retryAfter(HttpHeaders headers) {
        return Optional.ofNullable(headers.getFirst(HttpHeaders.RETRY_AFTER))
                .flatMap(RestClientOpenMeteoTransport::retryAfter);
    }

    private static Optional<Duration> retryAfter(String rawValue) {
        try {
            long seconds = Long.parseLong(rawValue.strip());
            if (seconds < 0) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static OpenMeteoTransportException accessFailure(ResourceAccessException ex) {
        if (isTimeout(ex)) {
            return OpenMeteoTransportException.timeout(ex);
        }
        return OpenMeteoTransportException.ioFailure(ex);
    }

    private static boolean isTimeout(ResourceAccessException ex) {
        return ex.contains(SocketTimeoutException.class)
                || messageMentionsTimeout(ex)
                || messageMentionsTimeout(ex.getMostSpecificCause());
    }

    private static boolean messageMentionsTimeout(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String message = throwable.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("timed out");
    }
}
