package id.co.nativeapp.finance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Test helper that proves a record landed on a dead-letter topic. It uses EXPLICIT partition
 * assignment (not subscribe) and seeks to the beginning, so it reads every record on the topic
 * deterministically without waiting on a consumer-group rebalance — a fresh subscribe + single
 * short poll would frequently return empty while the group is still being assigned, flaking the
 * assertion. It polls in a short loop and returns as soon as a record with the wanted key is seen.
 */
final class DltProbe {

  private DltProbe() {}

  /**
   * Returns {@code true} if, within {@code overall}, a record with {@code wantedKey} is found on
   * {@code topic}. Polls from the start of every partition with a fresh single-use consumer group.
   */
  static boolean awaitKeyOnTopic(
      String bootstrapServers, String topic, String wantedKey, Duration overall) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG,
            "dlt-probe-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            ByteArrayDeserializer.class);
    long deadline = System.nanoTime() + overall.toNanos();
    try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
      while (System.nanoTime() < deadline) {
        List<TopicPartition> partitions = partitionsOf(consumer, topic);
        if (partitions.isEmpty()) {
          // DLT not auto-created yet — the poison record has not been recovered. The
          // partitionsFor() metadata lookup above already blocked ~500ms, pacing this retry
          // loop without a Thread.sleep (forbidden in tests, §3.3).
          continue;
        }
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, byte[]> record : records) {
          if (wantedKey.equals(record.key())) {
            return true;
          }
        }
      }
      return false;
    }
  }

  private static List<TopicPartition> partitionsOf(
      KafkaConsumer<String, byte[]> consumer, String topic) {
    List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofMillis(500));
    List<TopicPartition> partitions = new ArrayList<>();
    if (infos != null) {
      for (PartitionInfo info : infos) {
        partitions.add(new TopicPartition(topic, info.partition()));
      }
    }
    return partitions;
  }
}
