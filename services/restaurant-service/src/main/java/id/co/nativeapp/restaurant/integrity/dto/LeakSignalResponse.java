package id.co.nativeapp.restaurant.integrity.dto;

import id.co.nativeapp.restaurant.integrity.domain.LeakSeverity;
import id.co.nativeapp.restaurant.integrity.domain.LeakSignalType;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * One raised signal in the sales-leak report: what kind, how serious, how much it might be worth,
 * and the evidence behind it.
 *
 * <p>A signal with no occurrences is NOT returned — an empty list of signals is the report saying
 * "nothing stood out", which is a different and more honest statement than nine signals each
 * reporting zero.
 *
 * @param type the machine signal kind; also the i18n key suffix the client renders copy from
 * @param severity how much attention it deserves
 * @param occurrences how many times this signal fired (items short, days unclosed, hours dark)
 * @param estimatedValueMinor the money this signal might represent, or {@code null} when the signal
 *     is real but carries no defensible amount (a session left open costs nothing by itself)
 * @param currency the ISO-4217 code for {@code estimatedValueMinor}, {@code null} when there is
 *     none
 * @param details the evidence rows, most significant first, capped by the reader
 */
public record LeakSignalResponse(
    LeakSignalType type,
    LeakSeverity severity,
    long occurrences,
    @Nullable Long estimatedValueMinor,
    @Nullable String currency,
    List<LeakDetailResponse> details) {}
