package id.co.nativeapp.finance.consolidation.service;

import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Binds a two-GUC group scope ({@code callAsGroup(lead, group)}) and runs an action that throws
 * only unchecked exceptions, rewrapping the checked {@link Exception} {@link
 * TenantContext#callAsGroup} declares (P3d #43).
 *
 * <p>This is the ONE place the {@code callAsGroup} + try/catch + checked-exception-rewrap
 * boilerplate lives. {@link GroupConsolidationService} (the request-side read/close binding) and
 * {@link GroupCloseService} (the consumer-driven close) both delegate here instead of each
 * repeating the pattern, so the rewrap is uniform and a future fix lands in a single spot. {@code
 * callAsGroup} declares a checked {@code Exception} only because its {@link Callable} signature
 * does; the actions actually passed (the reader, the writer's close) throw only unchecked
 * exceptions, so the {@code catch (Exception)} arm is unreachable in practice and rewrapped
 * defensively as an {@link IllegalStateException}.
 */
final class GroupScopeRunner {

  private GroupScopeRunner() {}

  /**
   * Binds {@code callAsGroup(lead, group)} with {@code actor} and runs {@code action}, returning
   * its value. A {@link RuntimeException} propagates unchanged; the (unreachable) checked {@link
   * Exception} is rewrapped as an {@link IllegalStateException} carrying {@code failureMessage}.
   *
   * @param lead the group's LEAD company (the bound tenant); the {@code company_id} group tables
   *     carry
   * @param groupId the bound consolidation group id
   * @param actor the acting principal (stamped into {@code created_by} for any write)
   * @param failureMessage the message for the defensive rewrap of a checked failure
   * @param action the (unchecked-throwing) work to run inside the group scope
   * @param <T> the result type
   */
  static <T> T runInGroupScope(
      UUID lead, UUID groupId, String actor, String failureMessage, Callable<T> action) {
    try {
      return TenantContext.callAsGroup(lead.toString(), groupId.toString(), actor, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAsGroup declares checked Exception; the actions throw only unchecked, so unreachable.
      throw new IllegalStateException(failureMessage, e);
    }
  }
}
