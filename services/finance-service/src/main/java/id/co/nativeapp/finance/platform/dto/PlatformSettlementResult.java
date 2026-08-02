package id.co.nativeapp.finance.platform.dto;

import id.co.nativeapp.finance.platform.domain.PlatformSettlement;

/**
 * Outcome of a settle attempt: the (possibly replayed) settlement row and whether THIS request
 * created it — the controller's 201-vs-200 replay signal (ENGINEERING-STANDARDS §1.1).
 */
public record PlatformSettlementResult(PlatformSettlement settlement, boolean created) {}
