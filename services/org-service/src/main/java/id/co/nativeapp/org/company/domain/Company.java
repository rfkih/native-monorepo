package id.co.nativeapp.org.company.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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

  /**
   * The company's country: an ISO 3166-1 alpha-2 code (e.g. {@code "ID"}). Write-once like {@code
   * base_currency} — the base currency is DERIVED from it at creation (ADR 0025), so a mutable
   * country would break the derivation story. Same {@code CHAR}/bpchar mapping trick as {@code
   * base_currency} so {@code ddl-auto=validate} agrees with the migrated column.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "country", nullable = false, updatable = false, length = 2)
  private String country;

  /**
   * The whitelisted plan tiers (ADR 0044, "Simple mode" for UMKM): {@code FREE} shows a curated
   * console (POS, products, receipts, register close, a simple sales summary, basic expenses, team
   * + printer settings); {@code FULL} shows the whole back-office. A TIER, not a boolean, so a real
   * paid tier (e.g. {@code PRO}/{@code ENTERPRISE}) ranks in later without a migration — same
   * whitelist-in-the-aggregate pattern as {@code country} (V9) / {@code OrgUnit.vertical} (V6): no
   * {@code CHECK} constraint, the invariant lives here.
   */
  public static final Set<String> PLAN_TIERS = Set.of("FREE", "FULL");

  /**
   * The company's plan tier — UI curation only (ADR 0044 D7: NOT a security boundary; API
   * authorization stays with the gateway/service role checks). Unlike {@code base_currency} /
   * {@code country}, this column is MUTABLE: an owner flips it via {@link
   * id.co.nativeapp.org.company.service.PlanTierWriter}. Defaults to {@code "FULL"} so a company
   * built through the all-args constructor (which does not yet accept a caller-supplied tier — P1
   * scope) matches the {@code V10} column's grandfather default; every existing/newly-bootstrapped
   * company reads as {@code FULL} until an owner explicitly opts into {@code FREE} (or a future
   * create-path default, P2).
   */
  @Column(name = "plan_tier", nullable = false, length = 16)
  private String planTier = "FULL";

  /** Optional contact phone captured at signup — format-checked at the request edge only. */
  @Column(name = "phone", length = 32)
  private String phone;

  /** Optional signup funnel field: employee-count band (whitelist lives at the request edge). */
  @Column(name = "company_size", length = 16)
  private String companySize;

  /** Optional signup funnel field: why the owner signed up (whitelist at the request edge). */
  @Column(name = "primary_interest", length = 32)
  private String primaryInterest;

  protected Company() {
    // for JPA
  }

  /**
   * Creates a company with the given id (which is also its tenant {@code company_id}). Validates
   * its invariants: non-blank name, a real ISO-4217 {@code baseCurrency}, non-blank {@code
   * defaultLanguage}, non-null {@code legalEmployerId}, a real ISO 3166-1 {@code country}.
   *
   * @param id the company id — also its own tenant id (a company is its own tenant)
   * @param name the company name; must be non-blank
   * @param baseCurrency the ISO-4217 base currency code; validated via {@link Currency}; IMMUTABLE
   * @param defaultLanguage the company default language (e.g. {@code "en"}/{@code "id"}); non-blank
   * @param legalEmployerId the legal employer this company is
   * @param country the ISO 3166-1 alpha-2 country code; validated via {@link CountryDefaults};
   *     IMMUTABLE (the base currency is derived from it at creation — ADR 0025)
   * @param phone optional contact phone (nullable)
   * @param companySize optional employee-count band (nullable — funnel data)
   * @param primaryInterest optional signup interest (nullable — funnel data)
   */
  public Company(
      UUID id,
      String name,
      String baseCurrency,
      String defaultLanguage,
      UUID legalEmployerId,
      String country,
      String phone,
      String companySize,
      String primaryInterest) {
    this.id = Objects.requireNonNull(id, "id");
    this.name = requireNonBlank(name, "name");
    this.baseCurrency = requireValidCurrency(baseCurrency);
    this.defaultLanguage = requireNonBlank(defaultLanguage, "defaultLanguage");
    this.legalEmployerId = Objects.requireNonNull(legalEmployerId, "legalEmployerId");
    this.country = CountryDefaults.requireValidCountry(country);
    this.phone = phone;
    this.companySize = companySize;
    this.primaryInterest = primaryInterest;
  }

  /**
   * Convenience constructor for the pre-country call sites (tests, fixtures): defaults the country
   * to {@code "ID"} (every pre-ADR-0025 tenant is Indonesian) with no funnel data.
   *
   * @param id the company id — also its own tenant id
   * @param name the company name
   * @param baseCurrency the ISO-4217 base currency code
   * @param defaultLanguage the company default language
   * @param legalEmployerId the legal employer this company is
   */
  public Company(
      UUID id, String name, String baseCurrency, String defaultLanguage, UUID legalEmployerId) {
    this(id, name, baseCurrency, defaultLanguage, legalEmployerId, "ID", null, null, null);
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

  /** The immutable ISO 3166-1 alpha-2 country code (stripped of any {@code CHAR} padding). */
  public String getCountry() {
    return country.strip();
  }

  /** Optional contact phone; {@code null} when never captured. */
  public String getPhone() {
    return phone;
  }

  /** Optional employee-count band from signup; {@code null} on the in-app create path. */
  public String getCompanySize() {
    return companySize;
  }

  /** Optional signup interest; {@code null} on the in-app create path. */
  public String getPrimaryInterest() {
    return primaryInterest;
  }

  /** The company's plan tier ({@code FREE} | {@code FULL}) — see {@link #PLAN_TIERS}. */
  public String getPlanTier() {
    return planTier;
  }

  /**
   * Changes the plan tier — the ONE mutable settings-like field on this otherwise write-once-heavy
   * aggregate (D2: "a tier, not a boolean"). Validates against {@link #PLAN_TIERS}, normalizing
   * (strip + upper-case) exactly like {@link CountryDefaults#requireValidCountry(String)} / {@link
   * Vertical#fromKey(String)}.
   *
   * @param planTier the requested tier ({@code FREE} or {@code FULL}, any case, trimmed)
   * @throws IllegalArgumentException if {@code planTier} is null or not in {@link #PLAN_TIERS} —
   *     the same validation-exception type the aggregate already uses for a bad country/vertical
   *     value. {@link id.co.nativeapp.org.company.service.PlanTierWriter} is the only caller and
   *     catches this to report a stable {@code 422} at the API edge (via {@link
   *     id.co.nativeapp.org.company.service.InvalidPlanTierException}) rather than the generic
   *     {@code 400} the shared advice maps a bare {@code IllegalArgumentException} to.
   */
  public void changePlanTier(String planTier) {
    Objects.requireNonNull(planTier, "planTier");
    String normalized = planTier.strip().toUpperCase(Locale.ROOT);
    if (!PLAN_TIERS.contains(normalized)) {
      throw new IllegalArgumentException("Unsupported plan tier: " + planTier);
    }
    this.planTier = normalized;
  }

  // NOTE: there is deliberately NO setBaseCurrency(...) and NO setId(...)/setLegalEmployerId(...).
  // base_currency is write-once (CLAUDE.md "Settings live at creation"); exposing a setter — or a
  // mutable column (it is updatable=false) — would violate that invariant.
}
