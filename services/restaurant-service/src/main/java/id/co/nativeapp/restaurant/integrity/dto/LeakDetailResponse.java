package id.co.nativeapp.restaurant.integrity.dto;

import jakarta.annotation.Nullable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One concrete piece of evidence under a leak signal — the row an owner clicks into when the
 * headline number prompts "where?".
 *
 * <p>A single shape covers every signal, with the fields a given signal cannot fill left {@code
 * null}, rather than a response type per signal. That is a deliberate trade: the client renders
 * these through one component driven by the parent signal's i18n keys, and nine near-identical
 * record types would buy type-safety the JSON boundary erases anyway.
 *
 * <p>{@code subjectName} carries DATA (an ingredient or menu item's own name, a login id), never UI
 * copy — every label, unit word and explanation around it comes from the console's {@code
 * salesIntegrity.*} i18n block (rule 9).
 *
 * @param subjectId the ingredient / menu item / register session the finding concerns, if any
 * @param subjectName that subject's name as stored, if any — data, not copy
 * @param businessDate the outlet-local day the finding concerns, if it is about a day
 * @param hourOfDay the outlet-local hour (0-23), if the finding is about an hour
 * @param quantity units missing, sales counted, closes in a run — whatever the signal counts
 * @param quantityUnit the unit {@code quantity} is counted in when the subject HAS one an owner
 *     would not otherwise know — an ingredient's base unit (g / ml / pcs). {@code null} when the
 *     count needs no unit because the signal type already says what it counts (sales, days,
 *     closes). "600 missing" is ambiguous by a factor of a thousand for an ingredient a stock page
 *     displays in kg; "600 g missing" is not
 * @param valueMinor money in minor units (never a float), if this row carries money
 * @param currency the ISO-4217 code for {@code valueMinor}, {@code null} when there is no money
 */
public record LeakDetailResponse(
    @Nullable UUID subjectId,
    @Nullable String subjectName,
    @Nullable LocalDate businessDate,
    @Nullable Integer hourOfDay,
    @Nullable Long quantity,
    @Nullable String quantityUnit,
    @Nullable Long valueMinor,
    @Nullable String currency) {}
