package id.co.nativeapp.finance.inventory.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.inventory.domain.InventoryMethodAlreadyActiveException;
import id.co.nativeapp.finance.inventory.domain.InventoryMethodConfig;
import id.co.nativeapp.finance.inventory.domain.InventoryMethodSealedPeriodException;
import id.co.nativeapp.finance.inventory.dto.ActivationResult;
import id.co.nativeapp.finance.inventory.repository.InventoryMethodConfigRepository;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for perpetual-inventory ACTIVATION (ADR 0067 §5, Phase
 * D4) — the ONLY writer that ever inserts an {@code inventory_method_config} row. Owner-only at the
 * gateway; there is no auto-activation anywhere else in the system (every existing tenant stays
 * byte-identical/inactive until this is called explicitly).
 *
 * <p>In ONE transaction: (a) writes the election row ({@code method=PERPETUAL}, {@code
 * perpetual_active=true}, the cutover, {@code activated_at=now}); (b) when {@code
 * openingInventoryValueMinor > 0}, books a balanced opening-inventory entry {@code Dr INVENTORY
 * (1100) / Cr OPENING_BALANCE_EQUITY (3900)} — an ad-hoc 2-line entry via {@link
 * RoleAccountResolver} (the {@code StockReceivedWriter}/{@code StocktakeWriter}/{@code
 * OpeningBalanceWriter} precedent, no posting template needed), reusing exactly the ADR 0037
 * opening- balance idiom this ADR's own §5 calls for. A zero opening value activates WITHOUT a
 * journal (still a valid, common case — a brand-new company with nothing counted yet).
 *
 * <p><strong>Idempotent, once-only per company.</strong> {@code uq_inventory_method_config_company}
 * (V53) backstops "one activation ever" at the DB layer. A REQUIRED {@code Idempotency-Key}
 * (replay-by-key, same-payload -> 200, no second posting) mirrors {@code OpeningBalanceWriter}; an
 * ALREADY-active company (any key/payload combination other than an exact replay) is {@code 409} —
 * there is no re-activate/amend flow. A per-company advisory lock serializes concurrent first-time
 * activations so the loser replays/conflicts deterministically rather than racing the unique
 * constraint.
 */
@Component
public class InventoryMethodActivationWriter {

  private static final Pattern CUTOVER_PERIOD = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

  private final InventoryMethodConfigRepository configRepository;
  private final GeneralLedgerWriter generalLedgerWriter;
  private final RoleAccountResolver roleAccountResolver;
  private final LedgerPostingRepository ledgerPostingRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public InventoryMethodActivationWriter(
      InventoryMethodConfigRepository configRepository,
      GeneralLedgerWriter generalLedgerWriter,
      RoleAccountResolver roleAccountResolver,
      LedgerPostingRepository ledgerPostingRepository,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.configRepository = configRepository;
    this.generalLedgerWriter = generalLedgerWriter;
    this.roleAccountResolver = roleAccountResolver;
    this.ledgerPostingRepository = ledgerPostingRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  /**
   * @throws InventoryMethodAlreadyActiveException the company is already active under a different
   *     key/payload (409) — activation is once-only, no second opening entry is ever booked
   * @throws InventoryMethodSealedPeriodException {@code cutoverPeriod} is tax-sealed (422)
   * @throws MismatchedPostingCurrencyException the cutover period already has a divergent GL
   *     currency (422)
   * @throws IllegalArgumentException malformed {@code cutoverPeriod}/{@code currency}/negative
   *     value
   */
  @Transactional
  public ActivationResult activate(
      String cutoverPeriod,
      long openingInventoryValueMinor,
      String currency,
      String idempotencyKey) {
    Objects.requireNonNull(cutoverPeriod, "cutoverPeriod");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (!CUTOVER_PERIOD.matcher(cutoverPeriod).matches()) {
      throw new IllegalArgumentException("cutoverPeriod must be YYYY-MM: " + cutoverPeriod);
    }
    if (openingInventoryValueMinor < 0) {
      throw new IllegalArgumentException(
          "openingInventoryValueMinor must not be negative: " + openingInventoryValueMinor);
    }
    String normalizedCurrency = currency.strip().toUpperCase(Locale.ROOT);

    String companyId = TenantContext.require().companyId();

    // Serialize concurrent first-time activations for this tenant (the OpeningBalanceWriter /
    // PlatformSettlementWriter idiom): the loser blocks until the winner COMMITS, then the re-probe
    // below replays/conflicts instead of colliding on uq_inventory_method_config_company as an
    // opaque 500.
    jdbcTemplate.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))", "inventory-method-activate:" + companyId);

    Optional<InventoryMethodConfig> existing = configRepository.findAll().stream().findFirst();
    if (existing.isPresent()) {
      InventoryMethodConfig row = existing.get();
      boolean sameKey = idempotencyKey.equals(row.getIdempotencyKey());
      boolean samePayload =
          sameKey
              && row.matchesPayload(cutoverPeriod, normalizedCurrency, openingInventoryValueMinor);
      if (samePayload) {
        return new ActivationResult(row, false);
      }
      throw new InventoryMethodAlreadyActiveException(
          "perpetual inventory is already active for this company — activation is once-only and"
              + " cannot be repeated or amended");
    }

    if (ledgerPostingRepository.sealedPeriodExists(cutoverPeriod)) {
      throw new InventoryMethodSealedPeriodException(
          "cutover period "
              + cutoverPeriod
              + " is sealed (a tax filing exists) — perpetual inventory cannot activate into it");
    }

    Instant activatedAt = clock.instant();
    UUID openingEntryId = null;
    if (openingInventoryValueMinor > 0) {
      Money value = Money.ofMinor(openingInventoryValueMinor, normalizedCurrency);
      requireConsistentGlCurrency(cutoverPeriod, value);
      Instant occurredAt = periodStart(cutoverPeriod);
      openingEntryId = UUID.randomUUID();
      JournalEntry entry =
          buildOpeningInventoryEntry(openingEntryId, cutoverPeriod, occurredAt, value);
      persistEntry(entry, companyId);
    }

    InventoryMethodConfig config =
        InventoryMethodConfig.activate(
            cutoverPeriod,
            activatedAt,
            normalizedCurrency,
            openingInventoryValueMinor,
            openingEntryId,
            idempotencyKey);
    config.setCompanyId(companyId);
    configRepository.save(config);

    return new ActivationResult(config, true);
  }

  /**
   * Builds (but does not persist) the balanced opening-inventory entry: {@code Dr INVENTORY / Cr
   * OPENING_BALANCE_EQUITY}. Public + pure besides the resolver lookups so a unit test can assert
   * the exact legs (the {@code StockReceivedWriter#buildEntry} / {@code
   * OpeningBalanceWriter#buildOpeningEntry} pattern).
   *
   * @throws IllegalStateException if {@code value} is zero — the caller must never build an entry
   *     for a zero-value activation (checked by the {@code > 0} guard in {@link #activate})
   */
  public JournalEntry buildOpeningInventoryEntry(
      UUID entryId, String period, Instant occurredAt, Money value) {
    if (value.isZero()) {
      throw new IllegalStateException(
          "value is zero — no opening-inventory entry should be built for a zero activation");
    }
    String inventoryCode = requireMapped(AccountRole.INVENTORY, occurredAt);
    String obeCode = requireMapped(AccountRole.OPENING_BALANCE_EQUITY, occurredAt);

    List<JournalLine> lines = new ArrayList<>(2);
    lines.add(JournalLine.debit(entryId, 1, inventoryCode, value));
    lines.add(JournalLine.credit(entryId, 2, obeCode, value));

    boolean usesIllustrative =
        roleAccountResolver.anyIllustrative(
            occurredAt, AccountRole.INVENTORY, AccountRole.OPENING_BALANCE_EQUITY);

    return JournalEntry.balanced(
        entryId,
        period,
        occurredAt,
        "Opening inventory balance (perpetual-inventory activation)",
        value.currency().getCurrencyCode(),
        entryId,
        usesIllustrative,
        lines);
  }

  /**
   * The first instant of {@code period} ({@code YYYY-MM}), UTC — the entry's {@code occurredAt}.
   */
  private static Instant periodStart(String period) {
    return LocalDate.parse(period + "-01").atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private void persistEntry(JournalEntry entry, String companyId) {
    generalLedgerWriter.post(entry, companyId);
  }

  /** Fail loud on an unmapped role (V53/V55 seed both, effective 2000-01-01 — internal fault). */
  private String requireMapped(AccountRole role, Instant occurredAt) {
    String accountCode = roleAccountResolver.resolve(role, occurredAt);
    if (accountCode == null) {
      throw new IllegalStateException(
          "no role_account_map mapping for " + role + " at " + occurredAt);
    }
    return accountCode;
  }

  /** The single-base-currency guard (mirrors every other settlement/opening writer verbatim). */
  private void requireConsistentGlCurrency(String period, Money amount) {
    String incoming = amount.currency().getCurrencyCode();
    List<String> divergent =
        jdbcTemplate.query(
            "SELECT DISTINCT currency FROM journal_entry WHERE period = ? AND currency <> ?",
            (rs, rowNum) -> rs.getString(1).strip(),
            period,
            incoming);
    if (!divergent.isEmpty()) {
      throw new MismatchedPostingCurrencyException(period, divergent.getFirst(), incoming);
    }
  }
}
