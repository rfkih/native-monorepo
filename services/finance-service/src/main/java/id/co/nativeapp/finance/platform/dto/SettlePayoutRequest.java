package id.co.nativeapp.finance.platform.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request to record ONE payout covering everything a payer settled (ADR 0076 phase 2): a Shopee
 * transfer clears both the ShopeeFood receivable and the counter QRIS balance.
 *
 * <p>{@code netMinor} is entered ONCE — the figure the merchant reads off the bank statement. The
 * deduction is DERIVED ({@code Σ gross − net}) and split across the lines pro-rata, never
 * client-supplied: a merchant knows what landed in their account, not the MDR and commission rates
 * behind it.
 *
 * <p>Money rule 8: integer minor units + ISO-4217, never a float. Bean validation rejects a missing
 * amount at the edge — a missing {@code Long} must never deserialize into a silent 0 (the
 * register-close W5 lesson).
 */
public record SettlePayoutRequest(
    @NotBlank @Size(max = 32) String sourceCode,
    @NotEmpty @Valid List<PayoutLine> lines,
    @NotNull @PositiveOrZero Long netMinor,
    @NotBlank @Size(min = 3, max = 3) String currency) {

  /**
   * One source this payout cleared, and the gross it settled. {@code CARD} is accepted by the shape
   * but refused by the writer until a card fee account is mapped — booking a card acquirer's fee to
   * the marketplace fee account would misstate the P&amp;L.
   */
  public record PayoutLine(
      @NotBlank
          @Pattern(
              regexp = "MARKETPLACE|QRIS|CARD",
              message = "sourceKind must be MARKETPLACE, QRIS or CARD")
          String sourceKind,
      @NotBlank @Size(max = 32) String channelCode,
      @NotNull @Positive Long grossMinor) {}
}
