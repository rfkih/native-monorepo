package id.co.nativeapp.employee.timeoff.controller;

import id.co.nativeapp.employee.timeoff.domain.LeaveRequest;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.dto.CreateLeaveRequestRequest;
import id.co.nativeapp.employee.timeoff.dto.LeaveRequestResponse;
import id.co.nativeapp.employee.timeoff.dto.MyLeaveRequestResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestReader;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me/leave-requests} — the employee self-service surface (ADR 0033): submit
 * (create), list/read own requests, and cancel. The caller is resolved EXCLUSIVELY from the bound
 * tenant/actor (the {@code /me} idiom, rule 5) — there is no employee-id parameter anywhere. {@code
 * create}/{@code cancel} require an {@code Idempotency-Key} header (missing → 400); create has no
 * prior DRAFT (ADR 0033 §3), so it is itself the idempotency-guarded SUBMIT transition.
 */
@Tag(
    name = "My Leave Requests",
    description = "Self-service: submit, list, read, and cancel own leave requests")
@RestController
@RequestMapping("/api/v1/me/leave-requests")
public class MyLeaveRequestController {

  private final LeaveRequestService requestService;
  private final LeaveRequestReader requestReader;

  public MyLeaveRequestController(
      LeaveRequestService requestService, LeaveRequestReader requestReader) {
    this.requestService = requestService;
    this.requestReader = requestReader;
  }

  @Operation(summary = "Submit a leave request for the caller")
  @PostMapping
  public ResponseEntity<LeaveRequestResponse> create(
      @Valid @RequestBody CreateLeaveRequestRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    LeaveRequest created =
        requestService.create(
            LeaveType.valueOf(request.leaveType()),
            request.startDate(),
            request.endDate(),
            request.days(),
            idempotencyKey);
    LeaveRequestResponse body = LeaveRequestResponse.from(created);
    return ResponseEntity.created(URI.create("/api/v1/me/leave-requests/" + body.id())).body(body);
  }

  @Operation(summary = "Cancel the caller's own SUBMITTED leave request")
  @PostMapping("/{id}/cancel")
  public LeaveRequestResponse cancel(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return LeaveRequestResponse.from(requestService.cancel(id, idempotencyKey));
  }

  @Operation(summary = "List the caller's own leave requests (paginated)")
  @GetMapping
  public PageResponse<MyLeaveRequestResponse> list(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    return requestReader.myRequests(page, size);
  }

  @Operation(summary = "Get one of the caller's own leave requests")
  @GetMapping("/{id}")
  public LeaveRequestResponse get(@PathVariable UUID id) {
    return requestReader.myRequest(id);
  }
}
