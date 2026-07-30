package id.co.nativeapp.loyalty.earnrule.projection;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Native-query read projection over an {@code earn_rule} row — serves both the resolution query
 * (points-earning at ingest time) and the admin listing endpoint, never {@code SELECT *} of the
 * entity (CODE-STRUCTURE.md §3.3).
 */
public interface EarnRuleView {

  UUID getId();

  String getRuleVersion();

  long getPointsPerMinorBp();

  @Nullable Long getMinSaleMinor();

  String getProvenance();

  String getSourceNote();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();

  boolean isActive();
}
