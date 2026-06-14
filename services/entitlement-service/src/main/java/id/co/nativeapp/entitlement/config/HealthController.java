package id.co.nativeapp.entitlement.config;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint. {@code GET /healthz} returns 200 with a small status body — the lightweight
 * check every Native service exposes for load balancers and smoke tests (the richer actuator probes
 * live under {@code /actuator}). It is exempt from the tenant filter, so it carries no tenant
 * header and hits no database.
 */
@RestController
public class HealthController {

  @GetMapping("/healthz")
  public ResponseEntity<Map<String, String>> healthz() {
    return ResponseEntity.ok(
        Map.of(
            "status", "UP",
            "service", "entitlement-service"));
  }
}
