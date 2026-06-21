package id.co.nativeapp.errorinbox;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Fire-and-forget webhook client that POSTs an {@link AlertPayload} JSON to a configurable URL
 * whenever an error reaches an alert milestone (ADR 0005). Registered as a bean by {@link
 * ErrorInboxAutoConfiguration}.
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
 * native.alert.read-timeout-ms}) — engineering-standards §4 (every outbound client sets explicit
 * timeouts, never a library default).
 */
public class AlertWebhookClient {

  private static final Logger log = LoggerFactory.getLogger(AlertWebhookClient.class);

  /**
   * Bounded backlog of pending alerts before the oldest is discarded (a hung webhook sheds load).
   */
  private static final int ALERT_QUEUE_CAPACITY = 64;

  private final String webhookUrl;
  private final RestClient restClient;
  private final ExecutorService executor;

  public AlertWebhookClient(String webhookUrl, int connectTimeoutMs, int readTimeoutMs) {
    this.webhookUrl = webhookUrl;

    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
    factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

    this.restClient = RestClient.builder().requestFactory(factory).build();

    // Single daemon worker with a small BOUNDED queue: fire-and-forget, never blocks the caller.
    // Milestone alerts are sparse, but a hung endpoint (up to the read timeout per call) must not
    // let the queue grow without bound — DiscardOldestPolicy sheds the stalest queued alert so a
    // wedged webhook drops load instead of accumulating it.
    ThreadFactory threadFactory =
        r -> {
          Thread t = new Thread(r, "alert-webhook");
          t.setDaemon(true);
          return t;
        };
    this.executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(ALERT_QUEUE_CAPACITY),
            threadFactory,
            new ThreadPoolExecutor.DiscardOldestPolicy());
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
   * complete before forcing termination. Wired as the bean's {@code destroyMethod} by {@link
   * ErrorInboxAutoConfiguration}.
   */
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
