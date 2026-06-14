package id.co.nativeapp.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Fast, deterministic unit test for the shared {@code kafka} readiness indicator (no Docker): it
 * reports {@code DOWN} — not UP, and not hanging — when the broker is unreachable, proving the
 * bounded-timeout describe-cluster path and the failure mapping. The UP path against a live broker
 * is exercised end to end by the Kafka consumer services' Testcontainers suites (the readiness
 * probe over a real broker), so it is not duplicated here with a container.
 */
class KafkaReadinessHealthIndicatorTest {

  @Test
  void reportsDownWhenTheBrokerIsUnreachableWithinTheBoundedTimeout() {
    // An admin pointed at a black-holed address; the short timeout makes the probe fail FAST.
    KafkaAdmin kafkaAdmin =
        new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "10.255.255.1:9092"));
    KafkaReadinessHealthIndicator indicator = new KafkaReadinessHealthIndicator(kafkaAdmin, 300);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void copiesTheAdminConfigSoTheProbeIsSelfContained() {
    // The indicator must not require the live KafkaAdmin at check time beyond its config snapshot.
    KafkaAdmin kafkaAdmin =
        new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "10.255.255.1:9092"));
    KafkaReadinessHealthIndicator indicator = new KafkaReadinessHealthIndicator(kafkaAdmin, 300);

    // A second invocation still yields a deterministic DOWN (no NPE / state leak across calls).
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void reportsUpWithClusterDetailsWhenTheBrokerIsReachable() {
    // A fake admin describing a reachable single-node cluster, injected via the test seam — proves
    // the UP branch and the clusterId/nodeCount details without a live broker.
    Node node = new Node(0, "broker", 9092);
    DescribeClusterResult describe = mock(DescribeClusterResult.class);
    when(describe.clusterId()).thenReturn(KafkaFuture.completedFuture("test-cluster-id"));
    when(describe.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(node)));
    AdminClient adminClient = mock(AdminClient.class);
    when(adminClient.describeCluster()).thenReturn(describe);

    KafkaAdmin kafkaAdmin =
        new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092"));
    KafkaReadinessHealthIndicator indicator =
        new KafkaReadinessHealthIndicator(kafkaAdmin, 300, config -> adminClient);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("clusterId", "test-cluster-id");
    assertThat(health.getDetails()).containsEntry("nodeCount", 1);
  }
}
