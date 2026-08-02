package id.co.nativeapp.employee.timeoff.controller;

import id.co.nativeapp.employee.timeoff.domain.LeaveRequest;
import id.co.nativeapp.employee.timeoff.dto.ApproveTimeoffRequest;
import id.co.nativeapp.employee.timeoff.dto.LeaveRequestResponse;
import id.co.nativeapp.employee.timeoff.dto.LeaveRequestSummaryResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.dto.RejectTimeoffRequest;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestReader;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/leave-requests} — the manager/owner surface (ADR 0033): the tenant-wide list plus
 * approve/reject. {@code approve}/{@code reject} require an {@code Idempotency-Key} header (missing
 * → 400).
 */
@Tag(
    name = "Leave Requests",
    description = "Manager surface: the tenant-wide leave-request list, approve, and reject")
@RestController
@RequestMapping("/api/v1/leave-requests")
public class LeaveRequestController {

  private final LeaveRequestService requestService;
  private final LeaveRequestReader requestReader;

  public LeaveRequestController(
      LeaveRequestService requestService, LeaveRequestReader requestReader) {
    this.requestService = requestService;
    this.requestReader = requestReader;
  }

  @Operation(
      summary = "List leave requests for the tenant, optionally filtered by status (paginated)")
  @GetMapping
  public PageResponse<LeaveRequestSummaryResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return requestReader.forManager(status, page, size);
  }

  @Operation(summary = "Get one leave request")
  @GetMapping("/{id}")
  public LeaveRequestResponse get(@PathVariable UUID id) {
    return requestReader.one(id);
  }

  @Operation(summary = "Approve a SUBMITTED leave request")
  @PostMapping("/{id}/approve")
  public LeaveRequestResponse approve(
      @PathVariable UUID id,
      @Valid @RequestBody ApproveTimeoffRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    LeaveRequest approved = requestService.approve(id, request.note(), idempotencyKey);
    return LeaveRequestResponse.from(approved);
  }

  @Operation(summary = "Reject a SUBMITTED leave request — a note is required")
  @PostMapping("/{id}/reject")
  public LeaveRequestResponse reject(
      @PathVariable UUID id,
      @Valid @RequestBody RejectTimeoffRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    LeaveRequest rejected = requestService.reject(id, request.note(), idempotencyKey);
    return LeaveRequestResponse.from(rejected);
  }
}
