package id.co.nativeapp.org.user.controller;

import id.co.nativeapp.org.user.dto.MyPagesResponse;
import id.co.nativeapp.org.user.dto.ReplacePagesRequest;
import id.co.nativeapp.org.user.service.UserPageGrantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/users/{userId}/pages} and {@code /api/v1/users/me/pages} — per-login page grants
 * (adjustable, SUBTRACTIVE, console-only page access; roles remain the API authz boundary — see the
 * ADR).
 *
 * <p>{@code /me/pages} is allowed for every business role (a login reads its own access mode); the
 * gateway routes the exact path with a dedicated {@code @Order(HIGHEST_PRECEDENCE)} bean before the
 * general {@code /users/**} route (mirrors {@code /me/outlets}). The addressed-by-id endpoints are
 * owner/manager (the general {@code /users/**} DASHBOARD_ROLES route). Cross-tenant targets return
 * 404 (anti-enumeration).
 */
@Tag(
    name = "User Page Grants",
    description = "Per-login page access — owner/manager adjust, any login reads its own")
@RestController
@RequestMapping("/api/v1/users")
public class UserPageGrantController {

  private final UserPageGrantService pageGrantService;

  public UserPageGrantController(UserPageGrantService pageGrantService) {
    this.pageGrantService = pageGrantService;
  }

  @Operation(
      summary = "Get the caller's own page-access mode",
      description =
          "ALL (no grants → full role surface) or RESTRICTED + the granted page keys, for the"
              + " authenticated caller (resolved from the JWT sub). Allowed for every business"
              + " role; the /me/pages route must be ordered before /users/** at the gateway.")
  @GetMapping("/me/pages")
  public MyPagesResponse myPages() {
    return pageGrantService.pagesForMe();
  }

  @Operation(
      summary = "Get a user's page-access mode",
      description =
          "ALL | RESTRICTED + keys for the given user. Cross-tenant targets return 404. Requires"
              + " owner or manager (the /users/** gateway route).")
  @GetMapping("/{userId}/pages")
  public MyPagesResponse userPages(@PathVariable String userId) {
    return pageGrantService.pagesForUser(userId);
  }

  @Operation(
      summary = "Replace a user's page grants (PUT / replace-set)",
      description =
          "Atomically replaces the login's page-grant set (whitelist: pos | kitchen | menu | me)."
              + " An empty list clears grants back to the full role surface (to lock out entirely,"
              + " remove the role/login). 400 on any unknown key; 404 cross-tenant. Requires owner"
              + " or manager.")
  @PutMapping("/{userId}/pages")
  public MyPagesResponse replacePages(
      @PathVariable String userId, @Valid @RequestBody ReplacePagesRequest request) {
    return pageGrantService.replacePagesForUser(userId, request.pageKeys());
  }
}
