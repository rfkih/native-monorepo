package id.co.nativeapp.events;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.apache.avro.SchemaCompatibility.SchemaPairCompatibility;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;

/**
 * Avro serialization helper: encodes/decodes a {@link GenericRecord} to/from {@code byte[]} using
 * Avro's compact {@link BinaryEncoder}/{@link BinaryDecoder}, and checks schema
 * backward-compatibility before any schema evolves.
 *
 * <p>The concrete Native event schemas (e.g. {@code SaleRecorded}) arrive in later milestones; this
 * helper works against any {@link Schema}/{@link GenericRecord} so it can be exercised with an
 * inline test schema today and reused unchanged once real schemas exist.
 *
 * <p>CLAUDE.md rule 7 / ARCHITECTURE.md: event schema changes are backward-compatible ONLY. {@link
 * #isBackwardCompatible(Schema, Schema)} encodes that gate — a reader using the new schema must be
 * able to read data written with the old schema.
 */
public final class AvroSerde {

  private AvroSerde() {
    // static helper
  }

  /**
   * Serializes a record to Avro binary.
   *
   * @param record the record to encode; its {@link GenericRecord#getSchema() schema} is the writer
   *     schema
   * @return the encoded bytes
   */
  public static byte[] serialize(GenericRecord record) {
    Objects.requireNonNull(record, "record");
    Schema schema = record.getSchema();
    DatumWriter<GenericRecord> writer = new GenericDatumWriter<>(schema);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    try {
      writer.write(record, encoder);
      encoder.flush();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to serialize Avro record", e);
    }
    return out.toByteArray();
  }

  /**
   * Deserializes Avro binary back into a {@link GenericRecord} using {@code schema} as BOTH writer
   * and reader — the shape every consumer in this codebase uses, because the outbox ships raw Avro
   * with no writer schema on the wire and no Schema Registry to look one up.
   *
   * <p><strong>Tolerates a payload written before trailing optional fields were appended.</strong>
   * The fleet-wide convention is "evolve an event by APPENDING a nullable field with a default,
   * LAST" (see any {@code .avsc} in {@code libs/contracts}). That is genuinely backward-compatible
   * under Avro schema resolution — but resolution needs the WRITER schema, and on this path there
   * isn't one: a straight writer==reader decode of an older, shorter payload runs off the end of
   * the buffer and throws. Since consumers run {@code auto-offset-reset: earliest}, any new
   * consumer group, group reset, or environment rebuilt without {@code __consumer_offsets} replays
   * historical payloads straight into that — and every one of them would land on the DLT.
   *
   * <p>So on a decode failure this retries with the writer schema truncated to each shorter prefix
   * that ends before a DEFAULTED field, longest first, keeping {@code schema} as the reader. Avro
   * then fills the absent fields from their declared defaults, which is exactly what the convention
   * promises. The full schema is always attempted first, so a current payload never takes the
   * fallback; if no prefix decodes either, the ORIGINAL failure is thrown (a genuinely corrupt
   * payload still fails closed to the DLT, and reports the real error).
   *
   * <p>Only trailing fields that declare a default are ever dropped — removing a required field
   * would be a breaking change, and this must not paper over one.
   *
   * @param bytes the encoded data
   * @param schema the consumer's current schema, used as writer and reader
   * @return the decoded record
   */
  public static GenericRecord deserialize(byte[] bytes, Schema schema) {
    try {
      return deserialize(bytes, schema, schema);
    } catch (UncheckedIOException probablyOlderPayload) {
      for (Schema olderWriter : trailingDefaultedPrefixes(schema)) {
        try {
          return deserialize(bytes, olderWriter, schema);
        } catch (RuntimeException stillNo) {
          // Try the next-shorter prefix; the original failure is rethrown if none work.
        }
      }
      throw probablyOlderPayload;
    }
  }

  /**
   * The plausible earlier versions of {@code schema}: the same record with its trailing DEFAULTED
   * fields peeled off one at a time, longest first. A record with no trailing defaulted field (or a
   * non-record schema) yields nothing, so the caller's original failure stands.
   */
  private static java.util.List<Schema> trailingDefaultedPrefixes(Schema schema) {
    if (schema.getType() != Schema.Type.RECORD) {
      return java.util.List.of();
    }
    java.util.List<Schema.Field> fields = schema.getFields();
    java.util.List<Schema> prefixes = new java.util.ArrayList<>();
    for (int keep = fields.size() - 1; keep >= 1; keep--) {
      if (!fields.get(keep).hasDefaultValue()) {
        break; // a required field: nothing shorter than this was ever a legal earlier version
      }
      java.util.List<Schema.Field> kept = new java.util.ArrayList<>(keep);
      for (int i = 0; i < keep; i++) {
        Schema.Field f = fields.get(i);
        kept.add(new Schema.Field(f.name(), f.schema(), f.doc(), f.defaultVal(), f.order()));
      }
      Schema prefix =
          Schema.createRecord(
              schema.getName(), schema.getDoc(), schema.getNamespace(), schema.isError());
      prefix.setFields(kept);
      prefixes.add(prefix);
    }
    return prefixes;
  }

  /**
   * Deserializes Avro binary using Avro's schema-resolution rules, mapping data written with {@code
   * writerSchema} onto {@code readerSchema}. This is how a consumer reads an older event with its
   * newer (backward-compatible) schema.
   *
   * @param bytes the encoded data
   * @param writerSchema the schema the data was written with
   * @param readerSchema the schema to project the data onto
   * @return the decoded record
   */
  public static GenericRecord deserialize(byte[] bytes, Schema writerSchema, Schema readerSchema) {
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(writerSchema, "writerSchema");
    Objects.requireNonNull(readerSchema, "readerSchema");
    DatumReader<GenericRecord> reader = new GenericDatumReader<>(writerSchema, readerSchema);
    BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
    try {
      return reader.read(null, decoder);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to deserialize Avro record", e);
    }
  }

  /**
   * Checks whether {@code newSchema} can read data written with {@code oldSchema} — i.e. the change
   * from old to new is backward-compatible.
   *
   * @param oldSchema the previously-registered (writer) schema
   * @param newSchema the proposed (reader) schema
   * @return {@code true} if every record written with {@code oldSchema} is readable under {@code
   *     newSchema}
   */
  public static boolean isBackwardCompatible(Schema oldSchema, Schema newSchema) {
    return checkBackwardCompatibility(oldSchema, newSchema).getType()
        == SchemaCompatibilityType.COMPATIBLE;
  }

  /**
   * Runs Avro's full compatibility analysis (reader = {@code newSchema}, writer = {@code
   * oldSchema}). Returns the raw result so callers can surface the specific incompatibility (e.g.
   * in a contract-test failure message).
   */
  public static SchemaPairCompatibility checkBackwardCompatibility(
      Schema oldSchema, Schema newSchema) {
    Objects.requireNonNull(oldSchema, "oldSchema");
    Objects.requireNonNull(newSchema, "newSchema");
    // Avro convention: reader first, writer second. Backward-compatible means the NEW
    // schema (reader) can read data produced by the OLD schema (writer).
    return SchemaCompatibility.checkReaderWriterCompatibility(newSchema, oldSchema);
  }
}
