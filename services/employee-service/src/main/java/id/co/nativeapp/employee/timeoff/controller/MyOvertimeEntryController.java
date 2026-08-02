package id.co.nativeapp.employee.timeoff.controller;

import id.co.nativeapp.employee.timeoff.domain.DayKind;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntry;
import id.co.nativeapp.employee.timeoff.dto.CreateOvertimeEntryRequest;
import id.co.nativeapp.employee.timeoff.dto.MyOvertimeEntryResponse;
import id.co.nativeapp.employee.timeoff.dto.OvertimeEntryResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryReader;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryService;
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
 * {@code /api/v1/me/overtime-entries} — the employee self-service surface (ADR 0033), mirroring
 * {@link MyLeaveRequestController} for overtime entries.
 */
@Tag(
    name = "My Overtime Entries",
    description = "Self-service: log, list, read, and cancel own overtime entries")
@RestController
@RequestMapping("/api/v1/me/overtime-entries")
public class MyOvertimeEntryController {

  private final OvertimeEntryService entryService;
  private final OvertimeEntryReader entryReader;

  public MyOvertimeEntryController(
      OvertimeEntryService entryService, OvertimeEntryReader entryReader) {
    this.entryService = entryService;
    this.entryReader = entryReader;
  }

  @Operation(summary = "Log an overtime entry for the caller")
  @PostMapping
  public ResponseEntity<OvertimeEntryResponse> create(
      @Valid @RequestBody CreateOvertimeEntryRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    OvertimeEntry created =
        entryService.create(
            request.workDate(),
            request.minutes(),
            DayKind.valueOf(request.dayKind()),
            idempotencyKey);
    OvertimeEntryResponse body = OvertimeEntryResponse.from(created);
    return ResponseEntity.created(URI.create("/api/v1/me/overtime-entries/" + body.id()))
        .body(body);
  }

  @Operation(summary = "Cancel the caller's own SUBMITTED overtime entry")
  @PostMapping("/{id}/cancel")
  public OvertimeEntryResponse cancel(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return OvertimeEntryResponse.from(entryService.cancel(id, idempotencyKey));
  }

  @Operation(summary = "List the caller's own overtime entries (paginated)")
  @GetMapping
  public PageResponse<MyOvertimeEntryResponse> list(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    return entryReader.myEntries(page, size);
  }

  @Operation(summary = "Get one of the caller's own overtime entries")
  @GetMapping("/{id}")
  public OvertimeEntryResponse get(@PathVariable UUID id) {
    return entryReader.myEntry(id);
  }
}
