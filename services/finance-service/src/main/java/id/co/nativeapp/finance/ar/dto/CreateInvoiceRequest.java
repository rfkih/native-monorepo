package id.co.nativeapp.finance.ar.dto;

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
 * Request body to create a DRAFT invoice. Each line carries a description, whole-unit quantity, and
 * per-unit price in minor units; the server computes the line totals, subtotal, tax (illustrative
 * output VAT when {@code taxable}), and grand total — never the client.
 */
public record CreateInvoiceRequest(
    @NotNull UUID customerId,
    @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO-4217 code") String currency,
    boolean taxable,
    @NotEmpty @Valid List<LineRequest> lines) {

  /** One requested invoice line. */
  public record LineRequest(
      @NotBlank @Size(max = 500) String description,
      @Positive int quantity,
      @Positive long unitPriceMinor) {}
}
