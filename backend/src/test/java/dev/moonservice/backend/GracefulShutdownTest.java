package dev.moonservice.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GracefulShutdownTest {
    @Test
    void allowsInFlightRequestToCompleteDuringBoundedShutdown() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "logging.level.root=WARN")
                .run();
        BlockingController controller = context.getBean(BlockingController.class);
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        context.addApplicationListener(
                (ApplicationListener<ContextClosedEvent>) event -> shutdownStarted.countDown());

        try {
            assertEquals("graceful", context.getEnvironment().getProperty("server.shutdown"));
            assertEquals("30s", context.getEnvironment()
                    .getProperty("spring.lifecycle.timeout-per-shutdown-phase"));

            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/test/slow"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<String>> response = client.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofString());

            assertTrue(controller.requestStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(context::close);
            assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> shutdown.get(250, TimeUnit.MILLISECONDS));
            assertFalse(shutdown.isDone());

            controller.releaseRequest.countDown();

            HttpResponse<String> completedResponse = response.get(5, TimeUnit.SECONDS);
            assertEquals(200, completedResponse.statusCode());
            assertEquals("complete", completedResponse.body());
            shutdown.get(5, TimeUnit.SECONDS);
        } finally {
            controller.releaseRequest.countDown();
            if (context.isActive()) {
                context.close();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(BlockingController.class)
    static class TestApplication {
    }

    @RestController
    static class BlockingController {
        private final CountDownLatch requestStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRequest = new CountDownLatch(1);

        @GetMapping("/test/slow")
        String slowRequest() throws InterruptedException {
            requestStarted.countDown();
            if (!releaseRequest.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test request was not released");
            }
            return "complete";
        }
    }
}
