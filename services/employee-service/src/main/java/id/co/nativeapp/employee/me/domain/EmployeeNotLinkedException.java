package id.co.nativeapp.employee.me.domain;

/**
 * The calling login (the JWT sub in {@code X-Actor}) is not linked to any employee in the bound
 * tenant — every {@code /api/v1/me/**} read fails closed with a {@code 404} carrying the {@code
 * employee-not-linked} problem type so the console can render the "ask your manager to link your
 * login" state. The message carries no identifiers at all (the sub is the caller's own, but there
 * is nothing useful to echo).
 */
public class EmployeeNotLinkedException extends RuntimeException {

  public EmployeeNotLinkedException() {
    super("This login is not linked to an employee");
  }
}
