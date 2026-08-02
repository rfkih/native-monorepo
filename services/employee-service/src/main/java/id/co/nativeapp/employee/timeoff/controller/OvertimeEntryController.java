package id.co.nativeapp.employee.timeoff.controller;

import id.co.nativeapp.employee.timeoff.domain.OvertimeEntry;
import id.co.nativeapp.employee.timeoff.dto.ApproveTimeoffRequest;
import id.co.nativeapp.employee.timeoff.dto.OvertimeEntryResponse;
import id.co.nativeapp.employee.timeoff.dto.OvertimeEntrySummaryResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.dto.RejectTimeoffRequest;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryReader;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryService;
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
 * {@code /api/v1/overtime-entries} — the manager/owner surface (ADR 0033), mirroring {@link
 * LeaveRequestController} for overtime entries.
 */
@Tag(
    name = "Overtime Entries",
    description = "Manager surface: the tenant-wide overtime-entry list, approve, and reject")
@RestController
@RequestMapping("/api/v1/overtime-entries")
public class OvertimeEntryController {

  private final OvertimeEntryService entryService;
  private final OvertimeEntryReader entryReader;

  public OvertimeEntryController(
      OvertimeEntryService entryService, OvertimeEntryReader entryReader) {
    this.entryService = entryService;
    this.entryReader = entryReader;
  }

  @Operation(
      summary = "List overtime entries for the tenant, optionally filtered by status (paginated)")
  @GetMapping
  public PageResponse<OvertimeEntrySummaryResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return entryReader.forManager(status, page, size);
  }

  @Operation(summary = "Get one overtime entry")
  @GetMapping("/{id}")
  public OvertimeEntryResponse get(@PathVariable UUID id) {
    return entryReader.one(id);
  }

  @Operation(summary = "Approve a SUBMITTED overtime entry")
  @PostMapping("/{id}/approve")
  public OvertimeEntryResponse approve(
      @PathVariable UUID id,
      @Valid @RequestBody ApproveTimeoffRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    OvertimeEntry approved = entryService.approve(id, request.note(), idempotencyKey);
    return OvertimeEntryResponse.from(approved);
  }

  @Operation(summary = "Reject a SUBMITTED overtime entry — a note is required")
  @PostMapping("/{id}/reject")
  public OvertimeEntryResponse reject(
      @PathVariable UUID id,
      @Valid @RequestBody RejectTimeoffRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    OvertimeEntry rejected = entryService.reject(id, request.note(), idempotencyKey);
    return OvertimeEntryResponse.from(rejected);
  }
}
