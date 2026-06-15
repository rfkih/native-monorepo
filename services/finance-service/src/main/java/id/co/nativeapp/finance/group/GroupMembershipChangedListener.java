package id.co.nativeapp.finance.group;

import id.co.nativeapp.finance.revenue.MissingEventIdException;
import id.co.nativeapp.finance.revenue.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code GroupMembershipChanged} off Kafka into finance's local {@link GroupMember} read
 * model (P3d SEAM 1). Mirrors the {@code LaborCostAllocated} consumer path.
 *
 * <p><strong>Raw Avro bytes / idempotency / fail-closed.</strong> Same contract as {@link
 * GroupDefinedListener}: decode raw Avro via {@link GroupMembershipChangedSchema}, dedupe by the
 * durable {@code id} header (a missing/non-UUID header fails closed to the DLT via {@link
 * MissingEventIdException}), and an undecodable payload becomes a non-retryable {@link
 * GroupMembershipChangedDecodeException} routed to {@code GroupMembershipChanged.DLT}.
 *
 * <p><strong>Tenant resolved from the local read model.</strong> The event carries no {@code
 * lead_company_id}; {@link GroupReadModelService} resolves it from the local {@code group_lead}
 * mapping (rule 2). A membership event whose group has not yet been registered throws a RETRYABLE
 * {@link UnknownGroupException}, so the container retries until {@code GroupDefined} has landed.
 */
@Component
public class GroupMembershipChangedListener {

  private static final Logger log = LoggerFactory.getLogger(GroupMembershipChangedListener.class);

  private final GroupReadModelService service;

  public GroupMembershipChangedListener(GroupReadModelService service) {
    this.service = service;
  }

  /** Handles one record from the {@code GroupMembershipChanged} topic. */
  @KafkaListener(
      topics = GroupMembershipChangedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onGroupMembershipChanged(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    GroupMembershipChangedEvent event = decode(eventId, record);
    boolean applied = service.handleMembershipChanged(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered GroupMembershipChanged eventId={} (already processed)", eventId);
    }
  }

  private static GroupMembershipChangedEvent decode(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return GroupMembershipChangedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new GroupMembershipChangedDecodeException(
          "Failed to decode GroupMembershipChanged payload at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " (eventId="
              + eventId
              + "); routing to DLT",
          decodeFailure);
    }
  }

  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(SaleRecordedListener.EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "GroupMembershipChanged record at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " has no '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header; routing to DLT (fail closed)");
    }
    String raw = new String(header.value(), StandardCharsets.UTF_8).strip();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException badId) {
      throw new MissingEventIdException(
          "GroupMembershipChanged '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
