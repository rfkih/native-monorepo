package id.co.nativeapp.finance.consolidation.controller;

import id.co.nativeapp.finance.consolidation.dto.GroupCloseResponse;
import id.co.nativeapp.finance.consolidation.dto.GroupConsolidationResponse;
import id.co.nativeapp.finance.consolidation.dto.GroupRequester;
import id.co.nativeapp.finance.consolidation.service.GroupConsolidationService;
import id.co.nativeapp.finance.consolidation.service.GroupRequesterFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The group-consolidation HTTP surface (P3d SEAM 4b — the LAST consolidation seam): the authz-gated
 * group READ and group CLOSE endpoints over a (group, period).
 *
 * <ul>
 *   <li><strong>{@code GET /api/v1/groups/{groupId}/consolidation?period=YYYY-MM}</strong> — the
 *       group-consolidation READ: returns the consolidated/eliminated group total + the provisional
 *       flags + the close state + the group eliminations status (the {@link
 *       GroupConsolidationResponse}). NEVER a per-member standalone trial balance or ledger. {@code
 *       204} when no close exists for the period yet.
 *   <li><strong>{@code POST
 *       /api/v1/groups/{groupId}/consolidation/close?period=YYYY-MM&closeRunSeq=N}</strong> — the
 *       group CLOSE command (lead-admin only): triggers the existing close pipeline and returns the
 *       result state ({@link GroupCloseResponse}).
 * </ul>
 *
 * <p><strong>The security crux is in {@link GroupConsolidationService}, off the verified identity —
 * never the path.</strong> The controller builds the {@link GroupRequester} from the bound {@link
 * id.co.nativeapp.tenant.TenantContext} + the JWT roles ({@link GroupRequesterFactory}), then the
 * service (1) requires the group-level role and (2) validates the requester's company IS the lead
 * of the requested group against the authoritative read model BEFORE binding the group scope. A
 * missing role → {@code 403}; a group the requester does not lead → {@code 404} (no existence
 * disclosure). No validated grant → the group scope is never bound → the group RLS matches nothing
 * (fail closed).
 *
 * <p>The {@code groupId} in the path is a CANDIDATE only — it is validated against the requester's
 * provable lead binding, never trusted to bind a scope by itself. RFC-7807 ProblemDetail bodies
 * come from {@code GroupConsolidationAdvice} (the 403/404 denials) and the shared {@code
 * ApiExceptionHandler} (validation 400).
 */
@RestController
@RequestMapping("/api/v1/groups/{groupId}/consolidation")
@Validated
public class GroupConsolidationController {

  private final GroupConsolidationService consolidationService;
  private final GroupRequesterFactory requesterFactory;

  public GroupConsolidationController(
      GroupConsolidationService consolidationService, GroupRequesterFactory requesterFactory) {
    this.consolidationService = consolidationService;
    this.requesterFactory = requesterFactory;
  }

  /**
   * The group-consolidation READ for {@code (groupId, period)}. {@code 200} with the group total +
   * flags + eliminations status; {@code 204} when no close exists for the period; {@code 403} / 404
   * on a denied entitlement.
   *
   * @param groupId the consolidation group (candidate — validated against the requester's lead)
   * @param period the accounting period {@code YYYY-MM} (validated; an impossible month is a 400)
   */
  @GetMapping
  public ResponseEntity<GroupConsolidationResponse> read(
      @PathVariable UUID groupId,
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period) {
    GroupRequester requester = requesterFactory.current();
    return consolidationService
        .readConsolidation(requester, groupId, period)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /**
   * Triggers a group CLOSE for {@code (groupId, period)} at {@code closeRunSeq} (lead-admin only).
   * {@code 200} with the result state; {@code 403} / 404 on a denied entitlement.
   *
   * @param groupId the consolidation group (candidate — validated against the requester's lead)
   * @param period the accounting period {@code YYYY-MM} (validated)
   * @param closeRunSeq the close run sequence (a higher seq supersedes lower ones; {@code >= 1})
   */
  @PostMapping("/close")
  public ResponseEntity<GroupCloseResponse> close(
      @PathVariable UUID groupId,
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period,
      @RequestParam @Min(value = 1, message = "closeRunSeq must be >= 1") int closeRunSeq) {
    GroupRequester requester = requesterFactory.current();
    GroupCloseResponse response =
        consolidationService.closeGroup(requester, groupId, period, closeRunSeq);
    return ResponseEntity.ok(response);
  }
}
