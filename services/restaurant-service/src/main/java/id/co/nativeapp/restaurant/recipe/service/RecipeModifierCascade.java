package id.co.nativeapp.restaurant.recipe.service;

import id.co.nativeapp.restaurant.menu.service.ModifierOptionCascade;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The recipe feature's {@link ModifierOptionCascade} (ADR 0050 phase A): when modifier options are
 * hard-deleted, their per-option recipe deltas are deleted in the SAME transaction — historical
 * sale lines keep their snapshots (unchanged), but no orphaned delta may keep skewing HPP or
 * depletion.
 *
 * <p>Delegates the delete to {@link RecipeWriter} (repositories are a service-layer concern — the
 * ArchUnit {@code repositoriesAreAccessedOnlyByTheServiceLayer} suffix rule).
 */
@Component
public class RecipeModifierCascade implements ModifierOptionCascade {

  private final RecipeWriter writer;

  public RecipeModifierCascade(RecipeWriter writer) {
    this.writer = writer;
  }

  @Override
  public void onOptionsDeleting(Collection<UUID> optionIds) {
    writer.deleteForModifierOptions(optionIds);
  }
}
