package id.co.nativeapp.org.user.service;

import java.util.UUID;

/**
 * The users-per-unit read was requested for an org unit that cannot carry outlet assignments (a
 * TEAM — assignments target outlets; a business unit aggregates its child outlets). Mapped to
 * {@code 400 Bad Request} by {@link id.co.nativeapp.org.user.config.UserExceptionAdvice}.
 */
public class InvalidUnitUsersTargetException extends RuntimeException {

  public InvalidUnitUsersTargetException(UUID orgUnitId) {
    super("org unit " + orgUnitId + " is a team — users are assigned to outlets");
  }
}
