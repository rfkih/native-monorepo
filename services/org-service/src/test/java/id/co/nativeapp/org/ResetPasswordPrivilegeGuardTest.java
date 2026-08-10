package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.org.company.service.CompanyReader;
import id.co.nativeapp.org.user.service.InsufficientPrivilegeException;
import id.co.nativeapp.org.user.service.KeycloakAdminClient;
import id.co.nativeapp.org.user.service.KeycloakUser;
import id.co.nativeapp.org.user.service.TeamAdministrationGuard;
import id.co.nativeapp.org.user.service.UserOutletAssignmentService;
import id.co.nativeapp.org.user.service.UserService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The role-hierarchy guard on {@code POST /api/v1/users/{id}/reset-password} (security-review
 * Finding 1). The gateway lets both owner and manager reach the endpoint, but a reset hands the
 * caller a working credential — so only an owner may reset a privileged (owner/manager) login,
 * otherwise a manager could reset the owner's password and take over the account.
 */
@ExtendWith(MockitoExtension.class)
class ResetPasswordPrivilegeGuardTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";

  @Mock private KeycloakAdminClient keycloak;
  @Mock private UserOutletAssignmentService outletAssignments;
  @Mock private TeamAdministrationGuard guard;
  @Mock private CompanyReader companyReader;

  private KeycloakUser user(String id, String role) {
    return new KeycloakUser(id, id + "@example.co.id", null, true, List.of(COMPANY), List.of(role));
  }

  @Test
  void managerCannotResetAnOwner() {
    UserService service = new UserService(keycloak, outletAssignments, guard, companyReader);
    when(keycloak.getUserById("owner-id")).thenReturn(Optional.of(user("owner-id", "owner")));
    when(keycloak.getUserById("mgr-sub")).thenReturn(Optional.of(user("mgr-sub", "manager")));

    TenantContext.runAs(
        COMPANY,
        "mgr-sub",
        () ->
            assertThatThrownBy(() -> service.resetPassword("owner-id"))
                .isInstanceOf(InsufficientPrivilegeException.class));

    verify(keycloak, never()).resetTemporaryPassword(any());
  }

  @Test
  void ownerCanResetAManager() {
    UserService service = new UserService(keycloak, outletAssignments, guard, companyReader);
    when(keycloak.getUserById("mgr-id")).thenReturn(Optional.of(user("mgr-id", "manager")));
    when(keycloak.getUserById("owner-sub")).thenReturn(Optional.of(user("owner-sub", "owner")));
    when(keycloak.resetTemporaryPassword("mgr-id")).thenReturn("NewTemp1!");

    TenantContext.runAs(
        COMPANY,
        "owner-sub",
        () ->
            assertThat(service.resetPassword("mgr-id").temporaryPassword()).isEqualTo("NewTemp1!"));
  }

  @Test
  void managerCanResetACashierWithoutFetchingTheCallersRoles() {
    UserService service = new UserService(keycloak, outletAssignments, guard, companyReader);
    when(keycloak.getUserById("csh-id")).thenReturn(Optional.of(user("csh-id", "cashier")));
    when(keycloak.resetTemporaryPassword("csh-id")).thenReturn("NewTemp2!");

    TenantContext.runAs(
        COMPANY,
        "mgr-sub",
        () ->
            assertThat(service.resetPassword("csh-id").temporaryPassword()).isEqualTo("NewTemp2!"));

    // The target is not privileged, so the caller's own roles are never resolved.
    verify(keycloak, never()).getUserById("mgr-sub");
  }
}
