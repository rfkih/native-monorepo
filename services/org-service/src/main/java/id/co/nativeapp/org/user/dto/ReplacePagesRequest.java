package id.co.nativeapp.org.user.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Request body for {@code PUT /api/v1/users/{userId}/pages} — the desired complete set of page keys
 * the login may open. An empty list clears all grants back to the full role surface (to lock
 * someone out entirely, remove the role/login instead). Keys are whitelisted server-side.
 *
 * @param pageKeys the complete desired page-key set (empty = full surface)
 */
public record ReplacePagesRequest(@NotNull List<String> pageKeys) {}
