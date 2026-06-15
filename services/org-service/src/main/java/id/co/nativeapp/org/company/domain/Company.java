package id.co.nativeapp.org.company.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code company} aggregate — org-service's tenant keystone: the legal employer, the console
 * scope, the consolidation scope, and the entitlement scope all in one. Creating a company creates
 * a new tenant.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code company} RLS policy in the Flyway baseline (rule 4 +
 * rule 5). A company's own {@code id} equals its {@code company_id} (it is its own tenant).
 *
 * <p><strong>base_currency is immutable (write-once).</strong> The base (functional) currency is
 * set once, at creation, and can NEVER change ("Settings live at creation"; CLAUDE.md). The field
 * is {@code final}-in-spirit: it is mapped {@code updatable = false} (so even a Hibernate flush
 * cannot rewrite the column), there is NO setter that reassigns it, and the only way to set it is
 * the all-args constructor — which validates it is a real ISO-4217 code via {@link
 * Currency#getInstance(String)} (never a float; an unknown code throws {@code
 * IllegalArgumentException}, mapped to a {@code 400}). The protected JPA constructor leaves it for
 * Hibernate to hydrate from the immutable column on read, never on write.
 *
 * <p>{@code default_language} is likewise set at creation (the dashboard never toggles it); a
 * per-user override lives on the user profile, not here. {@code legal_employer_id} records which
 * legal employer this company is.
 */
@Entity
@Table(name = "company")
public class Company extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  /**
   * The company's base (functional) currency: an ISO-4217 alphabetic code (e.g. {@code "IDR"} /
   * {@code "USD"}). Mapped as a fixed-width {@code CHAR(3)} (PostgreSQL {@code bpchar}); {@link
   * SqlTypes#CHAR} makes Hibernate's {@code ddl-auto=validate} agree with the migrated column type
   * (same trick as {@code MoneyEmbeddable}). PostgreSQL space-pads {@code CHAR} on read, so {@link
   * #getBaseCurrency()} strips before returning. <strong>{@code updatable = false}</strong> makes
   * the column write-once at the persistence layer — there is no path, in code or via Hibernate
   * dirty-checking, to change it after the row is inserted.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "base_currency", nullable = false, updatable = false, length = 3)
  private String baseCurrency;

  @Column(name = "default_language", nullable = false, length = 16)
  private String defaultLanguage;

  @Column(name = "legal_employer_id", nullable = false, updatable = false)
  private UUID legalEmployerId;

  protected Company() {
    // for JPA
  }

  /**
   * Creates a company with the given id (which is also its tenant {@code company_id}). Validates
   * its invariants: non-blank name, a real ISO-4217 {@code baseCurrency}, non-blank {@code
   * defaultLanguage}, non-null {@code legalEmployerId}.
   *
   * @param id the company id — also its own tenant id (a company is its own tenant)
   * @param name the company name; must be non-blank
   * @param baseCurrency the ISO-4217 base currency code; validated via {@link Currency}; IMMUTABLE
   * @param defaultLanguage the company default language (e.g. {@code "en"}/{@code "id"}); non-blank
   * @param legalEmployerId the legal employer this company is
   */
  public Company(
      UUID id, String name, String baseCurrency, String defaultLanguage, UUID legalEmployerId) {
    this.id = Objects.requireNonNull(id, "id");
    this.name = requireNonBlank(name, "name");
    this.baseCurrency = requireValidCurrency(baseCurrency);
    this.defaultLanguage = requireNonBlank(defaultLanguage, "defaultLanguage");
    this.legalEmployerId = Objects.requireNonNull(legalEmployerId, "legalEmployerId");
  }

  /**
   * Validates {@code baseCurrency} is a real ISO-4217 code (never a float — a currency is a code,
   * not an amount). Returns the normalized upper-case code; the persistence column is {@code
   * CHAR(3)}.
   */
  private static String requireValidCurrency(String baseCurrency) {
    Objects.requireNonNull(baseCurrency, "baseCurrency");
    String code = baseCurrency.strip().toUpperCase(java.util.Locale.ROOT);
    // Currency.getInstance throws IllegalArgumentException for an unknown code; this
    // is the single source of ISO-4217 validity, exactly as libs/money Money does.
    Currency.getInstance(code);
    return code;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  /** The immutable ISO-4217 base currency code (stripped of any {@code CHAR} padding). */
  public String getBaseCurrency() {
    return baseCurrency.strip();
  }

  public String getDefaultLanguage() {
    return defaultLanguage;
  }

  public UUID getLegalEmployerId() {
    return legalEmployerId;
  }

  // NOTE: there is deliberately NO setBaseCurrency(...) and NO setId(...)/setLegalEmployerId(...).
  // base_currency is write-once (CLAUDE.md "Settings live at creation"); exposing a setter — or a
  // mutable column (it is updatable=false) — would violate that invariant.
}
