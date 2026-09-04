package id.co.nativeapp.finance.ap.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request body to create a DRAFT bill. Each line carries a description, whole-unit quantity,
 * per-unit price in minor units, and an optional {@code inventory} flag (ADR 0067 Phase B, §3); the
 * server computes the line totals, subtotal, tax (illustrative input VAT when {@code taxable}), and
 * grand total — never the client.
 */
public record CreateBillRequest(
    @NotNull UUID vendorId,
    @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO-4217 code") String currency,
    boolean taxable,
    @NotEmpty @Valid List<LineRequest> lines) {

  /**
   * One requested bill line.
   *
   * @param inventory whether this line is an inventory purchase (ADR 0067 Phase B, §3) — read ONLY
   *     when the owning company is perpetual-active; ignored otherwise (every tenant in Phase B).
   *     {@code Boolean} (boxed), not {@code boolean}: Jackson's record deserializer treats a
   *     MISSING primitive creator property as an error, but tolerates a missing boxed one as {@code
   *     null} — so an old client that omits the field parses fine (never a 400) and reads as {@code
   *     null}, treated as {@code false} by {@link
   *     id.co.nativeapp.finance.ap.controller.BillController} (backward compatible).
   */
  public record LineRequest(
      @NotBlank @Size(max = 500) String description,
      @Positive int quantity,
      @Positive long unitPriceMinor,
      Boolean inventory,
      UUID ingredientId,
      @Size(max = 255) String ingredientName,
      Long ingredientQtyBase) {}
}
