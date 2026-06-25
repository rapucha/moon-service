package dev.moonservice.backend.weather.openmeteo;

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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

final class RestClientOpenMeteoWeatherTransport implements OpenMeteoWeatherTransport {
    private static final String USER_AGENT = "moon-service-backend/0.1";

    private final RestClient restClient;

    RestClientOpenMeteoWeatherTransport(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.clone().build();
    }

    RestClientOpenMeteoWeatherTransport(RestClient.Builder restClientBuilder, Duration timeout) {
        this.restClient = restClientBuilder.clone()
                .requestFactory(requestFactory(timeout))
                .build();
    }

    @Override
    public String get(URI requestUri, Duration timeout) throws OpenMeteoWeatherTransportException {
        try {
            return restClient.get()
                    .uri(requestUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .exchange((request, response) -> responseBody(response));
        } catch (ResourceAccessException ex) {
            throw accessFailure(ex);
        } catch (RestClientException ex) {
            throw OpenMeteoWeatherTransportException.ioFailure(ex);
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

    private static OpenMeteoWeatherTransportException httpFailure(int statusCode, HttpHeaders headers) {
        Optional<Duration> retryAfter = retryAfter(headers);
        if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return OpenMeteoWeatherTransportException.rateLimited(statusCode, retryAfter);
        }
        if (statusCode == HttpStatus.BAD_GATEWAY.value()
                || statusCode == HttpStatus.SERVICE_UNAVAILABLE.value()
                || statusCode == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return OpenMeteoWeatherTransportException.transientHttp(statusCode, retryAfter);
        }
        return OpenMeteoWeatherTransportException.nonRetryableHttp(statusCode, retryAfter);
    }

    private static Optional<Duration> retryAfter(HttpHeaders headers) {
        return Optional.ofNullable(headers.getFirst(HttpHeaders.RETRY_AFTER))
                .flatMap(RestClientOpenMeteoWeatherTransport::retryAfter);
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

    private static OpenMeteoWeatherTransportException accessFailure(ResourceAccessException ex) {
        if (isTimeout(ex)) {
            return OpenMeteoWeatherTransportException.timeout(ex);
        }
        return OpenMeteoWeatherTransportException.ioFailure(ex);
    }

    private static boolean isTimeout(Throwable throwable) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cursor = throwable; cursor != null && seen.add(cursor); cursor = cursor.getCause()) {
            if (cursor instanceof SocketTimeoutException) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
        }
        return false;
    }
}
