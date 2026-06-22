package dev.moonservice.backend.location.openmeteo;

import org.springframework.http.HttpHeaders;
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
import java.util.Optional;

final class RestClientOpenMeteoGeocodingTransport implements OpenMeteoGeocodingTransport {
    private static final String USER_AGENT = "moon-service-backend/0.1";

    private final RestClient restClient;

    RestClientOpenMeteoGeocodingTransport(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.clone().build();
    }

    RestClientOpenMeteoGeocodingTransport(RestClient.Builder restClientBuilder, Duration timeout) {
        this.restClient = restClientBuilder.clone()
                .requestFactory(requestFactory(timeout))
                .build();
    }

    @Override
    public String get(URI requestUri, Duration timeout) throws OpenMeteoGeocodingTransportException {
        try {
            return restClient.get()
                    .uri(requestUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .exchange((request, response) -> responseBody(response));
        } catch (ResourceAccessException ex) {
            throw accessFailure(ex);
        } catch (RestClientException ex) {
            throw OpenMeteoGeocodingTransportException.ioFailure(ex);
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

    private static OpenMeteoGeocodingTransportException httpFailure(int statusCode, HttpHeaders headers) {
        Optional<Duration> retryAfter = retryAfter(headers);
        if (statusCode == 429) {
            return OpenMeteoGeocodingTransportException.rateLimited(statusCode, retryAfter);
        }
        if (statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return OpenMeteoGeocodingTransportException.transientHttp(statusCode, retryAfter);
        }
        return OpenMeteoGeocodingTransportException.nonRetryableHttp(statusCode, retryAfter);
    }

    private static Optional<Duration> retryAfter(HttpHeaders headers) {
        return Optional.ofNullable(headers.getFirst(HttpHeaders.RETRY_AFTER))
                .flatMap(RestClientOpenMeteoGeocodingTransport::retryAfter);
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

    private static OpenMeteoGeocodingTransportException accessFailure(ResourceAccessException ex) {
        if (isTimeout(ex)) {
            return OpenMeteoGeocodingTransportException.timeout(ex);
        }
        return OpenMeteoGeocodingTransportException.ioFailure(ex);
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof SocketTimeoutException) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
