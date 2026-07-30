package id.co.nativeapp.loyalty.earnrule.service;

import id.co.nativeapp.loyalty.earnrule.dto.EarnRuleResponse;
import id.co.nativeapp.loyalty.earnrule.projection.EarnRuleView;
import id.co.nativeapp.loyalty.earnrule.repository.EarnRuleRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional(readOnly = true)} earn-rule reads. A distinct bean from {@link
 * EarnRuleWriter} so it is invoked through the Spring proxy.
 */
@Component
public class EarnRuleReader {

  private final EarnRuleRepository repository;

  public EarnRuleReader(EarnRuleRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public List<EarnRuleResponse> list(boolean activeOnly) {
    return repository.findViews(activeOnly).stream().map(EarnRuleReader::toResponse).toList();
  }

  private static EarnRuleResponse toResponse(EarnRuleView v) {
    return new EarnRuleResponse(
        v.getId(),
        v.getRuleVersion(),
        v.getPointsPerMinorBp(),
        v.getMinSaleMinor(),
        v.getProvenance(),
        v.getSourceNote(),
        v.getEffectiveFrom(),
        v.getEffectiveTo(),
        v.isActive());
  }
}
