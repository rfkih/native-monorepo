package id.co.nativeapp.org.group;

/**
 * The outcome of an add-member operation (P3d SEAM 1) — the resulting active {@link
 * GroupMembership} plus whether this call actually <em>created</em> a fresh membership.
 *
 * <p>It exists so the HTTP adapter can distinguish a genuinely new membership ({@code 201 Created}
 * + {@code Location}) from an idempotent no-op re-add of an already-ACTIVE member ({@code 200 OK},
 * no {@code Location}): the membership returned in either case is identical, so the boolean is the
 * only way to tell them apart at the boundary.
 *
 * @param membership the active membership for the company in the group (new or pre-existing)
 * @param created {@code true} if this call opened a fresh membership, {@code false} if the company
 *     was already an ACTIVE member (idempotent no-op)
 */
public record AddMemberResult(GroupMembership membership, boolean created) {}
