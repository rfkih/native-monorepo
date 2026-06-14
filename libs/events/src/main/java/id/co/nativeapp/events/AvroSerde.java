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
   * Deserializes Avro binary back into a {@link GenericRecord}.
   *
   * @param bytes the encoded data
   * @param writerSchema the schema the data was written with
   * @return the decoded record (read with {@code writerSchema} as both writer and reader)
   */
  public static GenericRecord deserialize(byte[] bytes, Schema writerSchema) {
    return deserialize(bytes, writerSchema, writerSchema);
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
