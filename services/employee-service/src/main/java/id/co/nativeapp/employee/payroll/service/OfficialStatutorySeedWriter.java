package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset;
import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset.DatasetComponent;
import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset.DatasetRule;
import id.co.nativeapp.employee.payroll.domain.PayComponent;
import id.co.nativeapp.employee.payroll.domain.PayrollSetupNotSeededException;
import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.domain.UnknownDatasetVersionException;
import id.co.nativeapp.employee.payroll.dto.SeedOfficialResponse;
import id.co.nativeapp.employee.payroll.repository.PayComponentRepository;
import id.co.nativeapp.employee.payroll.repository.StatutoryRuleRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activates a canned, versioned OFFICIAL statutory dataset (Track P phase P2, ADR 0031) for the
 * bound tenant — idempotent, per-tenant, same {@code @Transactional} writer idiom as {@link
 * IllustrativeStatutorySeedWriter}: every payroll table is under FORCE ROW LEVEL SECURITY, so the
 * seed must run inside a bound tenant scope (the auto-RLS aspect sets the GUC on this proxied
 * bean).
 *
 * <p>For every {@link DatasetRule} not already seeded at its exact {@code (rule_key,
 * rule_version)}: closes/deactivates any overlapping still-open row of the SAME {@code rule_key}
 * ({@link StatutoryRule#supersede}) — mirroring how {@code PayrollRunWriter.resolveStatutoryRules}
 * detects an ambiguous overlap, this is the write-side guard that prevents one — then inserts the
 * new row. For every {@link DatasetComponent}: inserts it if the {@code component_key} is new, else
 * aligns the existing row's fields ({@link PayComponent#applyCatalog}) — this is how {@code PPH21}
 * rewires from {@code PPH21_PROGRESSIVE} to {@code PPH21_TER} without a migration.
 *
 * <p>A re-run over the SAME dataset version is a byte-identical no-op (every rule/component is
 * already at its target state, so nothing is inserted/closed/updated) — the idempotent-reseed
 * proof.
 */
@Component
public class OfficialStatutorySeedWriter {

  private static final Logger log = LoggerFactory.getLogger(OfficialStatutorySeedWriter.class);

  private final PayComponentRepository payComponentRepository;
  private final StatutoryRuleRepository statutoryRuleRepository;

  public OfficialStatutorySeedWriter(
      PayComponentRepository payComponentRepository,
      StatutoryRuleRepository statutoryRuleRepository) {
    this.payComponentRepository = payComponentRepository;
    this.statutoryRuleRepository = statutoryRuleRepository;
  }

  /**
   * Activates {@code datasetVersion} for the bound tenant.
   *
   * @throws UnknownDatasetVersionException (→ 404) if no classpath dataset matches
   * @throws PayrollSetupNotSeededException (→ 409) if the tenant has no statutory rule on file yet
   *     (the currency source — see the class javadoc on {@link
   *     id.co.nativeapp.employee.payroll.dto.SeedOfficialRequest})
   */
  @Transactional
  public SeedOfficialResponse seed(String datasetVersion) {
    String tenant = TenantContext.require().companyId();
    OfficialStatutoryDataset.Dataset dataset =
        OfficialStatutoryDataset.load(datasetVersion)
            .orElseThrow(() -> new UnknownDatasetVersionException(datasetVersion));

    String currency =
        statutoryRuleRepository
            .findFirstByActiveTrue()
            .map(StatutoryRule::getCurrency)
            .orElseThrow(() -> new PayrollSetupNotSeededException(tenant));

    int rulesInserted = 0;
    int rulesClosed = 0;
    int rulesSkipped = 0;
    for (DatasetRule rule : dataset.rules()) {
      if (statutoryRuleRepository
          .findByRuleKeyAndRuleVersion(rule.ruleKey(), rule.ruleVersion())
          .isPresent()) {
        rulesSkipped++;
        continue;
      }
      List<StatutoryRule> overlapping =
          statutoryRuleRepository.findByRuleKeyAndActiveTrueAndEffectiveToGreaterThanEqual(
              rule.ruleKey(), rule.effectiveFrom());
      for (StatutoryRule prior : overlapping) {
        prior.supersede(rule.effectiveFrom());
        statutoryRuleRepository.save(prior);
        rulesClosed++;
      }
      StatutoryRule fresh =
          new StatutoryRule(
              rule.ruleKey(),
              rule.ruleVersion(),
              rule.calcType(),
              rule.paramsJson(),
              currency,
              rule.provenance(),
              rule.sourceNote(),
              rule.effectiveFrom(),
              StatutoryRule.OPEN_ENDED);
      fresh.setCompanyId(tenant);
      statutoryRuleRepository.save(fresh);
      rulesInserted++;
    }

    int componentsInserted = 0;
    int componentsUpdated = 0;
    for (DatasetComponent component : dataset.components()) {
      Optional<PayComponent> existing =
          payComponentRepository.findByComponentKey(component.componentKey());
      if (existing.isPresent()) {
        PayComponent found = existing.get();
        boolean changed =
            found.applyCatalog(
                component.kind(),
                component.calcType(),
                component.bearer(),
                component.glAccount(),
                component.taxable(),
                component.statutoryRuleKey(),
                component.displayOrder());
        if (changed) {
          payComponentRepository.save(found);
          componentsUpdated++;
        }
      } else {
        PayComponent fresh =
            new PayComponent(
                component.componentKey(),
                component.kind(),
                component.calcType(),
                component.bearer(),
                component.glAccount(),
                component.taxable(),
                component.statutoryRuleKey(),
                component.displayOrder());
        fresh.setCompanyId(tenant);
        payComponentRepository.save(fresh);
        componentsInserted++;
      }
    }

    log.info(
        "payroll-setup dataset {} activated for tenant {}: {} rule(s) inserted, {} closed, {}"
            + " skipped; {} component(s) inserted, {} updated",
        dataset.datasetVersion(),
        tenant,
        rulesInserted,
        rulesClosed,
        rulesSkipped,
        componentsInserted,
        componentsUpdated);

    return new SeedOfficialResponse(
        dataset.datasetVersion(),
        rulesInserted,
        rulesClosed,
        rulesSkipped,
        componentsInserted,
        componentsUpdated);
  }
}
