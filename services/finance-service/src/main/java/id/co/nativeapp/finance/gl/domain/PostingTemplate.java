package id.co.nativeapp.finance.gl.domain;

import java.util.List;
import java.util.Objects;

/**
 * An ordered set of {@link TemplateLine}s for a given {@link EventKind}, loaded from the {@code
 * posting_template} reference table. Value type — no JPA mapping.
 *
 * @param eventKind the event kind this template handles
 * @param version the template version (higher = newer; the resolver picks the effective one)
 * @param usesIllustrative whether this template carries the illustrative-placeholder flag (V13
 *     seeds are all {@code true}; an SME replaces them with higher-version rows flagged {@code
 *     false})
 * @param lines ordered template lines (≥2 expected, enforced by the balanced invariant)
 */
public record PostingTemplate(
    EventKind eventKind, int version, boolean usesIllustrative, List<TemplateLine> lines) {

  public PostingTemplate {
    Objects.requireNonNull(eventKind, "eventKind");
    Objects.requireNonNull(lines, "lines");
    lines = List.copyOf(lines);
  }
}
