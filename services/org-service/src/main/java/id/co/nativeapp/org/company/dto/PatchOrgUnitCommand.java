package id.co.nativeapp.org.company.dto;

import java.util.UUID;

/**
 * The application command to patch an org unit (rename / move / deactivate / reactivate). The
 * {@code orgUnitId} comes from the request path; the tenant scope is already bound at the edge, so
 * the writer operates within the bound company (rule 5).
 *
 * <p>{@code deactivate} and {@code reactivate} are mutually exclusive (the writer rejects a patch
 * that requests both). Deactivation CASCADES to the node's active descendants; reactivation applies
 * to the node alone and requires its parent to be active (the tree invariant: no active node may
 * have an inactive ancestor).
 *
 * @param orgUnitId the org unit to patch (from the path)
 * @param newName the new name when renaming, else {@code null}
 * @param reparent {@code true} to move the node under {@code newParentId} ({@code null} = top
 *     level)
 * @param newParentId the new parent when {@code reparent} is true
 * @param deactivate {@code true} to deactivate the node (and cascade to its active subtree)
 * @param reactivate {@code true} to reactivate the node (requires an active parent)
 */
public record PatchOrgUnitCommand(
    UUID orgUnitId,
    String newName,
    boolean reparent,
    UUID newParentId,
    boolean deactivate,
    boolean reactivate) {}
