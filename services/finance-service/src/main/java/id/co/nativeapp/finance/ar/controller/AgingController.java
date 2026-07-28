package id.co.nativeapp.finance.ar.controller;

import id.co.nativeapp.finance.ar.dto.AgingResponse;
import id.co.nativeapp.finance.ar.service.AgingReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/ar/aging} — aged receivables for the bound tenant, bucketed by days overdue vs an
 * {@code asOf} date (defaulting to today). RLS-scoped; the outstanding invoices come from the bound
 * tenant only.
 */
@Tag(name = "AR Aging", description = "Aged receivables for the bound tenant.")
@RestController
@RequestMapping("/api/v1/ar")
public class AgingController {

  private final AgingReader agingReader;

  public AgingController(AgingReader agingReader) {
    this.agingReader = agingReader;
  }

  @Operation(summary = "AR aging report as of a date (defaults to today), bucketed by days overdue")
  @GetMapping("/aging")
  public AgingResponse aging(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf) {
    return agingReader.aging(asOf);
  }
}
