package id.co.nativeapp.restaurant.selforderaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.restaurant.config.ActorRolesProvider;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccess;
import id.co.nativeapp.restaurant.selforderaccess.dto.SelfOrderAccessResponse;
import id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessReader;
import id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessService;
import id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessWriter;
import id.co.nativeapp.restaurant.table.service.TableReader;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Deterministic proof of {@link SelfOrderAccessService#rotate}'s conflict-recovery branch — the one
 * a concurrent rotate (or a rotate racing a first-provision {@code getActive}) triggers — without
 * depending on thread timing. Mirrors {@code SaleServiceConflictRecoveryTest}.
 *
 * <p>When {@link SelfOrderAccessWriter#rotate} throws either a {@link
 * DataIntegrityViolationException} (the losing insert against the one-ACTIVE-per-outlet partial
 * unique index) or an {@link OptimisticLockingFailureException} (the losing retire of an
 * already-retired row), {@code rotate} must NOT propagate a 500: it re-reads the current ACTIVE row
 * via {@link SelfOrderAccessReader#findActive} (a fresh transaction) and mints tokens off it. If
 * that re-read finds nothing — a genuine failure, not a concurrency race — the original exception
 * propagates.
 */
class SelfOrderAccessServiceRotateConflictRecoveryTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "owner@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private final SelfOrderAccessReader reader = mock(SelfOrderAccessReader.class);
  private final SelfOrderAccessWriter writer = mock(SelfOrderAccessWriter.class);
  private final TableReader tableReader = mock(TableReader.class);
  private final ActorRolesProvider actorRoles = new ActorRolesProvider();
  private final SelfOrderAccessService service =
      new SelfOrderAccessService(reader, writer, tableReader, actorRoles);

  private final SelfOrderAccess concurrentWinner =
      new SelfOrderAccess(
          OUTLET, "aabbccddeeff0011", Base64.getEncoder().encodeToString(new byte[32]));

  @Test
  void aUniqueIndexConflictRecoversTheConcurrentRotatesRowInstead() throws Exception {
    when(writer.rotate(OUTLET)).thenThrow(new DataIntegrityViolationException("dup active row"));
    when(reader.findActive(OUTLET)).thenReturn(Optional.of(concurrentWinner));
    when(tableReader.listByBusiness(any())).thenReturn(List.of());

    SelfOrderAccessResponse response =
        TenantContext.callAs(TENANT, ACTOR, () -> service.rotate(OUTLET));

    assertThat(response.kid()).isEqualTo(concurrentWinner.getKid());
  }

  @Test
  void anOptimisticLockConflictRecoversTheConcurrentRotatesRowInstead() throws Exception {
    when(writer.rotate(OUTLET)).thenThrow(new OptimisticLockingFailureException("already retired"));
    when(reader.findActive(OUTLET)).thenReturn(Optional.of(concurrentWinner));
    when(tableReader.listByBusiness(any())).thenReturn(List.of());

    SelfOrderAccessResponse response =
        TenantContext.callAs(TENANT, ACTOR, () -> service.rotate(OUTLET));

    assertThat(response.kid()).isEqualTo(concurrentWinner.getKid());
  }

  @Test
  void aConflictWithNoRecoverableRowRethrows() throws Exception {
    when(writer.rotate(OUTLET)).thenThrow(new DataIntegrityViolationException("dup active row"));
    when(reader.findActive(OUTLET)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> service.rotate(OUTLET)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
