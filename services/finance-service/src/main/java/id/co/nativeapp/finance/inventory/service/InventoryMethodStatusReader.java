package id.co.nativeapp.finance.inventory.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.inventory.domain.InventoryMethodConfig;
import id.co.nativeapp.finance.inventory.dto.InventoryMethodStatusResponse;
import id.co.nativeapp.finance.inventory.projection.InventoryAssetBalanceView;
import id.co.nativeapp.finance.inventory.repository.InventoryAssetBalanceRepository;
import id.co.nativeapp.finance.inventory.repository.InventoryMethodConfigRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of perpetual-inventory election status (ADR 0067 §5, Phase D4/D5) — owner/finance
 * roles (gateway FINANCE_ROLES). Answers "is this company active, and what is its LIVE {@code 1100}
 * balance" — the D5 negative-inventory monitor signal ({@code inventoryAssetNegative}) the console
 * surfaces per company.
 *
 * <p>{@code inventoryAssetMinor} is read straight off {@code journal_line} for account {@code 1100}
 * (Σdebit − Σcredit), independent of whether the company has ever activated — an ADR 0037 opening
 * balance can already carry a {@code 1100} line before any perpetual-inventory election, and once
 * Phase B/C consumers are posting, the SAME account reflects their activity too. This keeps the
 * status read a simple, always-correct GL query rather than a derived/cached figure.
 */
@Component
public class InventoryMethodStatusReader {

  private final InventoryMethodConfigRepository configRepository;
  private final InventoryAssetBalanceRepository balanceRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final Clock clock;

  public InventoryMethodStatusReader(
      InventoryMethodConfigRepository configRepository,
      InventoryAssetBalanceRepository balanceRepository,
      RoleAccountResolver roleAccountResolver,
      Clock clock) {
    this.configRepository = Objects.requireNonNull(configRepository, "configRepository");
    this.balanceRepository = Objects.requireNonNull(balanceRepository, "balanceRepository");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Must run inside a tenant-bound {@code @Transactional} scope so RLS applies (rule 5). */
  @Transactional(readOnly = true)
  public InventoryMethodStatusResponse read() {
    Optional<InventoryMethodConfig> config = configRepository.findAll().stream().findFirst();
    // Resolve INVENTORY via the role map (NOT a hardcoded "1100") so the D5 negative-asset monitor
    // keeps reading the RIGHT account if an SME ever remaps INVENTORY to a different code (ADR 0067
    // "the SME seam") — the writer + Phase B/C consumers post to the resolved account, and this
    // read
    // must track the same one.
    String inventoryCode = roleAccountResolver.resolve(AccountRole.INVENTORY, clock.instant());
    InventoryAssetBalanceView balance = balanceRepository.balanceFor(inventoryCode);
    long inventoryAssetMinor = balance.getTotalDebitMinor() - balance.getTotalCreditMinor();

    String currency =
        config
            .map(InventoryMethodConfig::getCurrency)
            .orElseGet(() -> balance.getCurrency() == null ? null : balance.getCurrency().strip());

    return new InventoryMethodStatusResponse(
        config.map(InventoryMethodConfig::isPerpetualActive).orElse(false),
        config.map(InventoryMethodConfig::getMethod).orElse(null),
        config.map(InventoryMethodConfig::getCutoverPeriod).orElse(null),
        config.map(InventoryMethodConfig::getActivatedAt).orElse(null),
        inventoryAssetMinor,
        inventoryAssetMinor < 0,
        currency);
  }
}
