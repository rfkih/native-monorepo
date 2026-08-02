package id.co.nativeapp.employee.timeoff.controller;

import id.co.nativeapp.employee.timeoff.dto.LeaveBalanceResponse;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me/leave-balance} — the caller's own DERIVED annual-leave balance (ADR 0033 §4). A
 * separate small controller from {@link MyLeaveRequestController} because it is a distinct resource
 * ({@code leave-balance}, singular, not a sub-path of {@code leave-requests}).
 */
@Tag(
    name = "My Leave Balance",
    description = "Self-service: the caller's own derived annual-leave balance")
@RestController
@RequestMapping("/api/v1/me/leave-balance")
public class MyLeaveBalanceController {

  private final LeaveBalanceReader balanceReader;

  public MyLeaveBalanceController(LeaveBalanceReader balanceReader) {
    this.balanceReader = balanceReader;
  }

  @Operation(
      summary =
          "The caller's own derived annual-leave balance for a year (defaults to the current year)")
  @GetMapping
  public LeaveBalanceResponse get(@RequestParam(required = false) Integer year) {
    return balanceReader.myBalance(year);
  }
}
