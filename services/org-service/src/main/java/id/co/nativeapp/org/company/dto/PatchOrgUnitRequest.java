package id.co.nativeapp.org.company.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.UUID;

/**
 * Patch-org-unit request body: {@code PATCH /api/v1/org-units/{orgUnitId}} — rename, move, and/or
 * deactivate a node. Every field is optional; only the operations whose intent flags are set are
 * applied, so a partial PATCH is unambiguous:
 *
 * <ul>
 *   <li><strong>rename</strong> — set {@code name} to the new name (non-blank);
 *   <li><strong>move</strong> — set {@code reparent=true} and {@code parentId} to the new parent
 *       (or {@code null} to move to the top level). The {@code reparent} flag is what disambiguates
 *       "move under no parent" ({@code parentId:null, reparent:true}) from "do not move" ({@code
 *       reparent} absent/false) — a bare {@code null} could mean either;
 *   <li><strong>deactivate</strong> — set {@code deactivate=true} (cascades to the active subtree);
 *   <li><strong>reactivate</strong> — set {@code reactivate=true} (requires an active parent).
 *       {@code deactivate} and {@code reactivate} are mutually exclusive (both → {@code 400}).
 * </ul>
 *
 * <p>The boolean intent flags are nullable {@link Boolean}s, not primitives, so an omitted flag
 * deserializes cleanly to {@code null} (a primitive would make Jackson reject a partial body that
 * omits it); {@link #reparent()} / {@link #deactivate()} normalize {@code null} to {@code false}.
 *
 * <p>An empty patch (no operation requested) is rejected with a {@code 400} from {@link
 * ApiExceptionHandler}; so is a blank rename or an illegal move (caught by the {@link OrgUnit}
 * aggregate / the writer's cycle + same-company checks). The company id comes from the bound tenant
 * scope, never the body (rule 5).
 *
 * @param name the new name when renaming; {@code null} to leave the name unchanged
 * @param reparentFlag {@code true} to move the node ({@code parentId} is then the new parent,
 *     possibly {@code null}); {@code null}/false to leave the parent unchanged
 * @param parentId the new parent when reparenting ({@code null} = move to top level)
 * @param deactivateFlag {@code true} to deactivate the node; {@code null}/false otherwise
 * @param reactivateFlag {@code true} to reactivate the node; {@code null}/false otherwise
 */
public record PatchOrgUnitRequest(
    String name,
    @JsonProperty("reparent") Boolean reparentFlag,
    UUID parentId,
    @JsonProperty("deactivate") Boolean deactivateFlag,
    @JsonProperty("reactivate") Boolean reactivateFlag) {

  /** Whether a move was requested (normalizing an omitted flag to {@code false}). */
  public boolean reparent() {
    return Boolean.TRUE.equals(reparentFlag);
  }

  /** Whether a deactivation was requested (normalizing an omitted flag to {@code false}). */
  public boolean deactivate() {
    return Boolean.TRUE.equals(deactivateFlag);
  }

  /** Whether a reactivation was requested (normalizing an omitted flag to {@code false}). */
  public boolean reactivate() {
    return Boolean.TRUE.equals(reactivateFlag);
  }

  /** Whether a rename was requested (a non-null name; blankness is validated by the aggregate). */
  public boolean isRename() {
    return name != null;
  }

  /** Whether this patch requests no operation at all (so it should be rejected as a 400). */
  public boolean isNoOp() {
    return !isRename() && !reparent() && !deactivate() && !reactivate();
  }
}
