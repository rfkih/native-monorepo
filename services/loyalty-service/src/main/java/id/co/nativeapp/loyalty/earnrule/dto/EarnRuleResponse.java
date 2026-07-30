package id.co.nativeapp.loyalty.earnrule.dto;

import java.time.LocalDate;
import java.util.UUID;

public record EarnRuleResponse(
    UUID id,
    String ruleVersion,
    long pointsPerMinorBp,
    Long minSaleMinor,
    String provenance,
    String sourceNote,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    boolean active) {}
