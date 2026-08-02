package id.co.nativeapp.employee.timeoff.controller;

import id.co.nativeapp.employee.timeoff.dto.AdjustLeaveBalanceRequest;
import id.co.nativeapp.employee.timeoff.dto.LeaveBalanceRowResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceReader;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/leave-balances} — the manager/owner surface (ADR 0033 §4): the per-employee
 * DERIVED balance list for a year, plus the adjustment (grant/correction) write.
 */
@Tag(
    name = "Leave Balances",
    description = "Manager surface: per-employee derived leave balances + adjustments")
@RestController
@RequestMapping("/api/v1/leave-balances")
public class LeaveBalanceController {

  private final LeaveBalanceReader balanceReader;
  private final LeaveBalanceWriter balanceWriter;
  private final Clock clock;

  public LeaveBalanceController(
      LeaveBalanceReader balanceReader, LeaveBalanceWriter balanceWriter, Clock clock) {
    this.balanceReader = balanceReader;
    this.balanceWriter = balanceWriter;
    this.clock = clock;
  }

  @Operation(
      summary =
          "List every employee's derived leave balance for a year (defaults to the current year, paginated)")
  @GetMapping
  public PageResponse<LeaveBalanceRowResponse> list(
      @RequestParam(required = false) Integer year,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return balanceReader.forManager(year, page, size);
  }

  @Operation(
      summary =
          "Grant/correct an employee's leave-balance adjustment for a year (defaults to the current year)")
  @PatchMapping("/{employeeId}")
  public LeaveBalanceRowResponse adjust(
      @PathVariable UUID employeeId,
      @RequestParam(required = false) Integer year,
      @Valid @RequestBody AdjustLeaveBalanceRequest request) {
    int resolvedYear = year == null ? LocalDate.now(clock).getYear() : year;
    balanceWriter.adjust(employeeId, resolvedYear, request.adjustmentDays());
    // Re-read via the reader so the response reflects the SAME derived-balance computation every
    // other endpoint uses (single source of truth — never re-derived here).
    return balanceReader.forEmployee(employeeId, resolvedYear);
  }
}
