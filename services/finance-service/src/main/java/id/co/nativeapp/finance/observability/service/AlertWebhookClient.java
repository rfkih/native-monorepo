package id.co.nativeapp.finance.observability.service;

import id.co.nativeapp.finance.observability.dto.AlertPayload;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fire-and-forget webhook client that POSTs an {@link AlertPayload} JSON to a configurable URL
 * whenever an error reaches an alert milestone (ADR 0005).
 *
 * <p>The POST runs on an internal single daemon thread ({@link ExecutorService}) so it is fully
 * async and never blocks or throws into the caller. All exceptions from the HTTP call are caught
 * and logged at WARN level.
 *
 * <p>When {@code native.alert.webhook-url} is blank (the default in dev / CI), this class is a
 * no-op — no outbound network call is ever made. This satisfies the dev/CI requirement that
 * alerting never calls out when running locally or in CI.
 *
 * <p>Explicit connect and read timeouts are set on the underlying {@link
 * SimpleClientHttpRequestFactory} (values from {@code native.alert.connect-timeout-ms} / {@code
 * native.alert.read-timeout-ms}), advancing engineering-standards scorecard gap #12.
 */
@Component
public class AlertWebhookClient {

  private static final Logger log = LoggerFactory.getLogger(AlertWebhookClient.class);

  private final String webhookUrl;
  private final RestClient restClient;
  private final ExecutorService executor;

  public AlertWebhookClient(
      @Value("${native.alert.webhook-url:}") String webhookUrl,
      @Value("${native.alert.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${native.alert.read-timeout-ms:3000}") int readTimeoutMs) {
    this.webhookUrl = webhookUrl;

    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
    factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

    this.restClient = RestClient.builder().requestFactory(factory).build();

    // Single daemon thread: fire-and-forget, never blocks the caller.
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "alert-webhook");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Enqueues an async fire-and-forget POST of {@code payload} to the configured webhook URL.
   *
   * <p>Does nothing when the URL is blank. All exceptions are caught inside the submitted task.
   *
   * @param payload the alert payload; must not be {@code null}
   */
  public void send(AlertPayload payload) {
    if (webhookUrl == null || webhookUrl.isBlank()) {
      return;
    }
    executor.submit(
        () -> {
          try {
            restClient
                .post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
          } catch (Exception e) {
            log.warn(
                "alert-webhook: POST to {} failed (fingerprint={}, occurrences={}): {}",
                webhookUrl,
                payload.fingerprint(),
                payload.occurrenceCount(),
                e.getMessage());
          }
        });
  }

  /**
   * Shuts down the internal executor on bean destruction, waiting briefly for any in-flight POST to
   * complete before forcing termination.
   */
  @PreDestroy
  public void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
