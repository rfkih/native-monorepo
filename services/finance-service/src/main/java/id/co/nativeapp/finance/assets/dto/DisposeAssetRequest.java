package id.co.nativeapp.finance.assets.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /api/v1/assets/{id}/dispose} body (ADR 0022). {@code proceedsMinor} is the sale price
 * in integer minor units of {@code currency} (rule 8) — {@code 0} means scrap/write-off. The
 * disposal date is metadata (the entry posts into the current period); it must not precede the
 * asset's acquisition date (writer-enforced).
 */
public record DisposeAssetRequest(
    @NotNull @PastOrPresent LocalDate disposalDate,
    @Min(0) long proceedsMinor,
    @NotBlank @Size(min = 3, max = 3) String currency) {}
