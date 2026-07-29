package id.co.nativeapp.finance.ap.controller;

import id.co.nativeapp.finance.ap.dto.AgingResponse;
import id.co.nativeapp.finance.ap.service.ApAgingReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/ap/aging} — aged payables for the bound tenant, bucketed by days overdue vs an
 * {@code asOf} date (defaulting to today). RLS-scoped; the outstanding bills come from the bound
 * tenant only.
 *
 * <p>Named {@code ApAgingController} (not the AR-mirrored {@code AgingController}) because Spring's
 * classpath component scan defaults a {@code @RestController}'s bean name to its simple class name
 * — {@code ar.controller.AgingController} and an identically-named {@code ap.controller
 * .AgingController} would collide ({@code ConflictingBeanDefinitionException}) once both packages
 * are on the classpath together.
 */
@Tag(name = "AP Aging", description = "Aged payables for the bound tenant.")
@RestController
@RequestMapping("/api/v1/ap")
public class ApAgingController {

  private final ApAgingReader apAgingReader;

  public ApAgingController(ApAgingReader apAgingReader) {
    this.apAgingReader = apAgingReader;
  }

  @Operation(summary = "AP aging report as of a date (defaults to today), bucketed by days overdue")
  @GetMapping("/aging")
  public AgingResponse aging(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf) {
    return apAgingReader.aging(asOf);
  }
}
