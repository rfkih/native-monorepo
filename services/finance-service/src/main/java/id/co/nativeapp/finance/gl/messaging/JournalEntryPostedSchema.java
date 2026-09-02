package id.co.nativeapp.finance.gl.messaging;

import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's {@code JournalEntryPosted} Avro schema from the classpath ({@code
 * avro/JournalEntryPosted.avsc} in {@code libs/contracts} — the single source of truth) and builds
 * the producer-side {@link GenericRecord} (ADR 0071 P1).
 *
 * <p>The event is emitted from exactly one place — {@code gl.service.GeneralLedgerWriter.post}, the
 * GL's one persistence door — so every persisted journal entry produces exactly one event by
 * construction (the {@code onlyTheGeneralLedgerWriterPersistsJournals} ArchUnit rule is what makes
 * that "exactly one place" structural rather than aspirational). No Avro code-generation plugin, no
 * Confluent / Schema Registry serde — raw bytes via {@code libs/events} {@code AvroSerde}, exactly
 * like the {@code TrialBalancePublished} / {@code ConsolidationClosed} paths. {@code
 * JournalEntryPostedContractTest} asserts the schema stays backward-compatible (rule 7).
 */
public final class JournalEntryPostedSchema {

  /** Classpath location of the {@code .avsc} (the shared libs/contracts copy). */
  public static final String RESOURCE = "avro/JournalEntryPosted.avsc";

  /** The Kafka topic / outbox {@code event_type} (one topic per event type). */
  public static final String TOPIC = "JournalEntryPosted";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "JournalEntryPosted";

  /**
   * The producing aggregate kind (outbox {@code aggregate_type}). The partition key (the outbox
   * {@code aggregate_id}) is the {@code company_id}, so one tenant's GL stream is totally ordered —
   * a REVERSAL contra is never consumed before the entry it supersedes.
   */
  public static final String AGGREGATE_TYPE = "journal_entry";

  private static final Schema SCHEMA = parse();

  private static final Schema LINE_SCHEMA = SCHEMA.getField("lines").schema().getElementType();

  private JournalEntryPostedSchema() {
    // static holder
  }

  /** The parsed schema for {@code JournalEntryPosted}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code JournalEntryPosted} {@link GenericRecord} from a persisted entry. Called only
   * by {@code GeneralLedgerWriter.post}, after the entry and its lines are saved in the caller's
   * transaction — the outbox row rides the same commit (rule 3).
   *
   * <p>{@code business_id} is emitted {@code null} until ADR 0071 P5 threads the outlet through the
   * choke point; {@code period} is the entry's authoritative value carried verbatim (payroll runs
   * post into a period that is not {@code periodOf(occurredAt)}).
   *
   * @param entry the persisted, balanced entry (lines still assembled on the transient list)
   * @param companyId the bound tenant the entry was stamped with
   */
  public static GenericRecord toRecord(JournalEntry entry, String companyId) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("journal_entry_id", entry.getId().toString());
    record.put("company_id", companyId);
    record.put("business_id", null);
    record.put("period", entry.getPeriod());
    record.put("occurred_at", entry.getOccurredAt().toEpochMilli());
    record.put("currency", entry.getCurrency());
    record.put("posting_role", entry.getPostingRole().name());
    record.put("source_event_id", entry.getSourceEventId().toString());
    List<GenericRecord> lineRecords = new ArrayList<>(entry.getLines().size());
    for (JournalLine line : entry.getLines()) {
      lineRecords.add(toLineRecord(line));
    }
    record.put("lines", lineRecords);
    return record;
  }

  private static GenericRecord toLineRecord(JournalLine line) {
    GenericRecord record = new GenericData.Record(LINE_SCHEMA);
    record.put("line_no", line.getLineNo());
    record.put("account_code", line.getAccountCode());
    record.put("debit_minor", line.getDebitMinor());
    record.put("credit_minor", line.getCreditMinor());
    record.put("currency", line.getCurrency());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        JournalEntryPostedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
