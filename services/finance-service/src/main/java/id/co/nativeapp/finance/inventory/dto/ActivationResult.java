package id.co.nativeapp.finance.inventory.dto;

import id.co.nativeapp.finance.inventory.domain.InventoryMethodConfig;

/**
 * Outcome of an activation attempt: the (possibly replayed) election row and whether THIS request
 * created it — the controller's 201-vs-200 replay signal (ENGINEERING-STANDARDS §1.1), mirroring
 * {@code OpeningBalanceResult}.
 */
public record ActivationResult(InventoryMethodConfig config, boolean created) {}
