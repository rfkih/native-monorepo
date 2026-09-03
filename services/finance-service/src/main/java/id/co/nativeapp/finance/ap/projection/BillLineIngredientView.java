package id.co.nativeapp.finance.ap.projection;

import java.util.UUID;

/**
 * Read projection for the ADR 0072 P4 event emission: the ingredient-linked lines of one posted
 * bill (id = the wire {@code line_id} / the restaurant {@code goods_receipt.idempotency_key},
 * ingredient, base-unit qty, and the NET line total that becomes the moving-average value). Reached
 * only from the service + repository layers.
 */
public interface BillLineIngredientView {

  UUID getId();

  UUID getIngredientId();

  long getIngredientQtyBase();

  long getLineTotalMinor();
}
