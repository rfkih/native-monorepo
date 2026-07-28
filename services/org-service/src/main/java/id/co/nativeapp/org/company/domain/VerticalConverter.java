package id.co.nativeapp.org.company.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter persisting {@link Vertical} as its LOWERCASE {@link Vertical#key()} — the one
 * guardrail that keeps the DB value module-key-cased. {@code @Enumerated(EnumType.STRING)} would
 * silently store the uppercase {@code .name()} (the exact casing-bug class the org-unit-hub
 * increment had to fix for {@code type}); this converter makes that impossible.
 */
@Converter
public class VerticalConverter implements AttributeConverter<Vertical, String> {

  @Override
  public String convertToDatabaseColumn(Vertical attribute) {
    return attribute == null ? null : attribute.key();
  }

  @Override
  public Vertical convertToEntityAttribute(String dbData) {
    return dbData == null ? null : Vertical.fromKey(dbData);
  }
}
