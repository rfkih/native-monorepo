package id.co.nativeapp.restaurant.menu.service;

import java.util.Collection;
import java.util.UUID;

/**
 * A same-transaction hook invoked by {@link ModifierWriter} JUST BEFORE modifier options are
 * hard-deleted. Defined HERE (menu) and implemented by downstream features — dependency inversion
 * so the menu feature never imports its consumers (the recipe feature implements this to
 * cascade-delete its per-option recipe deltas, ADR 0050; without it a deleted option would leave
 * orphaned deltas that silently skew HPP and depletion).
 */
public interface ModifierOptionCascade {

  /** Called inside the deleting transaction with the ids of the options about to be removed. */
  void onOptionsDeleting(Collection<UUID> optionIds);
}
