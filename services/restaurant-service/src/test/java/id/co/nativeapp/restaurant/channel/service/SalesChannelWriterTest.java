package id.co.nativeapp.restaurant.channel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.restaurant.channel.domain.SalesChannel;
import id.co.nativeapp.restaurant.channel.domain.SalesChannelCodeConflictException;
import id.co.nativeapp.restaurant.channel.domain.SalesChannelForbiddenException;
import id.co.nativeapp.restaurant.channel.domain.SalesChannelNotFoundException;
import id.co.nativeapp.restaurant.channel.dto.CreateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.dto.SalesChannelResponse;
import id.co.nativeapp.restaurant.channel.dto.UpdateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.projection.SalesChannelView;
import id.co.nativeapp.restaurant.channel.repository.SalesChannelRepository;
import id.co.nativeapp.restaurant.config.ActorRolesProvider;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * Unit pins for {@link SalesChannelWriter} (ADR 0036 Phase B2) — mirrors {@code
 * RegisterSessionWriterTest}'s style (mocked repository + collaborator, no Spring context): code
 * normalization at create, the duplicate-code 409 pre-check, the owner/manager role gate on
 * create/update (empty-roles-pass semantics), and that LIST is never gated.
 */
class SalesChannelWriterTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";

  private final SalesChannelRepository repository = mock(SalesChannelRepository.class);
  private final ActorRolesProvider actorRoles = mock(ActorRolesProvider.class);
  private final SalesChannelWriter writer = new SalesChannelWriter(repository, actorRoles);

  private static <T> T asTenant(Callable<T> action) {
    try {
      return TenantContext.callAs(COMPANY, "test", action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ── create: normalization + duplicate 409 ──────────────────────────────

  @Test
  void createNormalizesCodeToUppercaseTrimBeforePersisting() {
    when(actorRoles.currentRoles()).thenReturn(List.of("owner"));
    when(actorRoles.isOwnerOrManager()).thenReturn(true);
    when(repository.existsByCode("GOFOOD")).thenReturn(false);
    when(repository.saveAndFlush(any(SalesChannel.class))).thenAnswer(inv -> inv.getArgument(0));

    SalesChannelResponse response =
        asTenant(
            () -> writer.create(new CreateSalesChannelRequest("  gofood  ", "GoFood Delivery")));

    assertThat(response.code()).isEqualTo("GOFOOD");
    assertThat(response.name()).isEqualTo("GoFood Delivery");
    assertThat(response.active()).isTrue();
  }

  @Test
  void createWithADuplicateNormalizedCodeConflicts() {
    when(actorRoles.currentRoles()).thenReturn(List.of("manager"));
    when(actorRoles.isOwnerOrManager()).thenReturn(true);
    when(repository.existsByCode("GRABFOOD")).thenReturn(true);

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.create(
                            new CreateSalesChannelRequest("grabfood", "GrabFood Delivery"))))
        .isInstanceOf(SalesChannelCodeConflictException.class);
  }

  // ── role gate: create/update require owner/manager; empty-roles-pass ──

  @Test
  void createByACashierWithANonEmptyRoleSetIsForbidden() {
    when(actorRoles.currentRoles()).thenReturn(List.of("cashier"));
    when(actorRoles.isOwnerOrManager()).thenReturn(false);

    assertThatThrownBy(
            () -> asTenant(() -> writer.create(new CreateSalesChannelRequest("GOFOOD", "GoFood"))))
        .isInstanceOf(SalesChannelForbiddenException.class);
  }

  @Test
  void createByAHeaderlessCallerSucceedsDevRecipeTrustEmptyRolesPass() {
    when(actorRoles.currentRoles()).thenReturn(List.of());
    when(repository.existsByCode("GOFOOD")).thenReturn(false);
    when(repository.saveAndFlush(any(SalesChannel.class))).thenAnswer(inv -> inv.getArgument(0));

    SalesChannelResponse response =
        asTenant(() -> writer.create(new CreateSalesChannelRequest("GOFOOD", "GoFood")));

    assertThat(response.code()).isEqualTo("GOFOOD");
  }

  @Test
  void updateByACashierIsForbidden() {
    when(actorRoles.currentRoles()).thenReturn(List.of("cashier"));
    when(actorRoles.isOwnerOrManager()).thenReturn(false);
    UUID id = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                asTenant(
                    () -> writer.update(id, new UpdateSalesChannelRequest("New name", null))))
        .isInstanceOf(SalesChannelForbiddenException.class);
  }

  // ── update: name + active, code stays immutable ────────────────────────

  @Test
  void updateRenamesAndCanDeactivate() {
    when(actorRoles.currentRoles()).thenReturn(List.of("owner"));
    when(actorRoles.isOwnerOrManager()).thenReturn(true);
    SalesChannel channel = new SalesChannel("GOFOOD", "GoFood");
    channel.setCompanyId(COMPANY);
    when(repository.findById(channel.getId())).thenReturn(Optional.of(channel));
    when(repository.saveAndFlush(channel)).thenReturn(channel);

    SalesChannelResponse response =
        asTenant(
            () ->
                writer.update(
                    channel.getId(), new UpdateSalesChannelRequest("GoFood v2", false)));

    assertThat(response.name()).isEqualTo("GoFood v2");
    assertThat(response.active()).isFalse();
    // code is immutable — untouched by the update.
    assertThat(response.code()).isEqualTo("GOFOOD");
  }

  @Test
  void updateOfAnUnknownIdIsNotFound() {
    when(actorRoles.currentRoles()).thenReturn(List.of("owner"));
    when(actorRoles.isOwnerOrManager()).thenReturn(true);
    UUID unknownId = UUID.randomUUID();
    when(repository.findById(unknownId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                asTenant(
                    () -> writer.update(unknownId, new UpdateSalesChannelRequest("x", null))))
        .isInstanceOf(SalesChannelNotFoundException.class);
  }

  // ── list: never role-gated (the POS checkout channel picker) ──────────

  @Test
  void listIsNeverRoleGatedEvenForACashier() {
    when(actorRoles.currentRoles()).thenReturn(List.of("cashier"));
    SalesChannelView view = mock(SalesChannelView.class);
    when(view.getId()).thenReturn(UUID.randomUUID());
    when(view.getCode()).thenReturn("GOFOOD");
    when(view.getName()).thenReturn("GoFood");
    when(view.isActive()).thenReturn(true);
    when(repository.findAllViews()).thenReturn(List.of(view));

    List<SalesChannelResponse> responses = asTenant(writer::list);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).code()).isEqualTo("GOFOOD");
  }
}
