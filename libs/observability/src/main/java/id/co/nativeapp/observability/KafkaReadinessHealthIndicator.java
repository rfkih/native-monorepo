package id.co.nativeapp.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * The shared {@code kafka} readiness health indicator (ENGINEERING-STANDARDS §7).
 *
 * <p>Spring Boot 4 ships NO built-in Kafka health contributor, yet every Native Kafka <em>consumer
 * </em> service names {@code kafka} in its {@code
 * management.endpoint.health.group.readiness.include} so the pod reports {@code NOT_READY} (and is
 * pulled from rotation) while its broker is down or its read model is still hydrating. This
 * indicator fills that gap: registered under the name {@code kafka} by {@link
 * KafkaReadinessHealthAutoConfiguration}, it describes the cluster over a <em>short-timeout</em>
 * admin client and maps a reachable broker to {@code UP}, an unreachable one to {@code DOWN}.
 *
 * <p>The timeout is bounded explicitly (never the admin client's long default) so a readiness probe
 * fails FAST when the broker is unreachable rather than hanging — the §4 resilience rule applied to
 * the health check itself.
 */
public class KafkaReadinessHealthIndicator extends AbstractHealthIndicator {

  private final Map<String, Object> adminConfig;
  private final int requestTimeoutMs;
  private final Function<Map<String, Object>, AdminClient> adminClientFactory;

  /**
   * @param kafkaAdmin the service's Spring {@link KafkaAdmin}, whose configuration (bootstrap
   *     servers, security) seeds the probe's admin client
   * @param requestTimeoutMs the bounded describe-cluster timeout in milliseconds (fail fast)
   */
  public KafkaReadinessHealthIndicator(KafkaAdmin kafkaAdmin, int requestTimeoutMs) {
    this(kafkaAdmin, requestTimeoutMs, AdminClient::create);
  }

  /**
   * Package-private seam: lets a unit test inject a fake {@link AdminClient} so the UP/DOWN mapping
   * is exercised deterministically without a live broker. Production always uses the public
   * constructor, which wires {@link AdminClient#create}.
   */
  KafkaReadinessHealthIndicator(
      KafkaAdmin kafkaAdmin,
      int requestTimeoutMs,
      Function<Map<String, Object>, AdminClient> adminClientFactory) {
    super("Kafka readiness check failed");
    this.adminConfig = new HashMap<>(kafkaAdmin.getConfigurationProperties());
    this.requestTimeoutMs = requestTimeoutMs;
    this.adminClientFactory = adminClientFactory;
  }

  @Override
  protected void doHealthCheck(Health.Builder builder) throws Exception {
    // A dedicated short-timeout admin client: never reuse the service's long-lived one and never
    // inherit its (possibly infinite) default timeouts — a readiness check must fail fast.
    Map<String, Object> config = new HashMap<>(adminConfig);
    config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
    config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, requestTimeoutMs);

    try (AdminClient adminClient = adminClientFactory.apply(config)) {
      DescribeClusterResult cluster = adminClient.describeCluster();
      String clusterId = cluster.clusterId().get(requestTimeoutMs, TimeUnit.MILLISECONDS);
      int nodeCount = cluster.nodes().get(requestTimeoutMs, TimeUnit.MILLISECONDS).size();
      builder.up().withDetail("clusterId", clusterId).withDetail("nodeCount", nodeCount);
    }
  }
}
