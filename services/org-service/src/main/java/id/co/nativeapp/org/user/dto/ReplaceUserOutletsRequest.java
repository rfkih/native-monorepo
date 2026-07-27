package id.co.nativeapp.org.user.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code PUT /api/v1/users/{userId}/outlets} — the replace-set (PUT semantics)
 * operation that atomically sets a user's outlet assignments.
 *
 * <p>An empty {@code orgUnitIds} list is valid and means "remove all assignments." The list may not
 * be {@code null} (a null body is rejected with 400). Duplicate ids in the list are silently
 * de-duplicated by the service layer before processing.
 *
 * @param orgUnitIds the complete desired set of outlet org-unit UUIDs for the user
 */
public record ReplaceUserOutletsRequest(@NotNull List<UUID> orgUnitIds) {}
