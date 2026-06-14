package id.co.nativeapp.finance.labor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives the DETERMINISTIC synthetic {@code source_event_id} a supersession REVERSAL posting
 * carries (#23). A REVERSAL cannot reuse the original posting's {@code source_event_id} (that UUID
 * is UNIQUE and already consumed by the original PRIMARY posting), so finance mints a new one
 * deterministically from the prior posting id — a type-3 (name-based, MD5) UUID over {@code
 * "<priorPostingId>:REVERSAL"}.
 *
 * <p><strong>Why deterministic matters for idempotency.</strong> Because the reversal id is a pure
 * function of the prior posting id, re-delivering the superseding run derives the SAME reversal
 * UUID — so {@code ProcessedEventStore.processOnce} + the {@code ledger_posting.source_event_id}
 * UNIQUE backstop make a second reversal a clean no-op. The prior run can never be reversed twice
 * (the supersession is itself idempotent under re-delivery — a HARD requirement).
 */
final class ReversalEventIds {

  private ReversalEventIds() {}

  /** The synthetic reversal {@code source_event_id} for a prior PRIMARY posting id. */
  static UUID forPriorPosting(UUID priorPostingId) {
    String name = priorPostingId + ":REVERSAL";
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }
}
