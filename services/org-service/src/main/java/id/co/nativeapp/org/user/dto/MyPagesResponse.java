package id.co.nativeapp.org.user.dto;

import java.util.List;

/**
 * The caller's page-access mode ({@code GET /api/v1/users/me/pages}) and the per-user editor read.
 * {@code mode = ALL} (zero grant rows) means the full role surface — the console applies no page
 * filter; {@code mode = RESTRICTED} means only {@code pageKeys} are open (still intersected with
 * the role surface console-side).
 *
 * @param mode {@code ALL} | {@code RESTRICTED}
 * @param pageKeys the granted page keys when RESTRICTED (empty when ALL)
 */
public record MyPagesResponse(String mode, List<String> pageKeys) {

  public static MyPagesResponse of(List<String> activeKeys) {
    return activeKeys.isEmpty()
        ? new MyPagesResponse("ALL", List.of())
        : new MyPagesResponse("RESTRICTED", activeKeys);
  }
}
