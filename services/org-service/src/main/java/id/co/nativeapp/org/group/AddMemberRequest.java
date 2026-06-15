package id.co.nativeapp.org.group;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Add-member request body: {@code POST /api/v1/consolidation-groups/{groupId}/members}. Adds a
 * member company to a group the calling tenant leads. The {@code groupId} comes from the path, the
 * lead from the bound tenant scope (rule 5); only the {@code memberCompanyId} is carried here.
 *
 * <p>A missing {@code memberCompanyId} fails fast with a {@code 400} from {@link
 * ApiExceptionHandler}.
 *
 * @param memberCompanyId the company to add to the group
 */
public record AddMemberRequest(@NotNull UUID memberCompanyId) {}
