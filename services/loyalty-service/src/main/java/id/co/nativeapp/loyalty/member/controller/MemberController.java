package id.co.nativeapp.loyalty.member.controller;

import id.co.nativeapp.loyalty.member.dto.EnrollMemberRequest;
import id.co.nativeapp.loyalty.member.dto.MemberResponse;
import id.co.nativeapp.loyalty.member.service.MemberService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/loyalty/members} — enroll/lookup/get for the sole PII home of the member phone +
 * display name (rule 6). The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never the request body (rule 5). This is a
 * DIRECT client call from the console/POS through the gateway — not a service-to-service call (see
 * ADR 0027: "console → gateway → loyalty-service is a client call, not a service-to-service call").
 */
@Tag(name = "Loyalty Members", description = "Enroll and look up loyalty members by phone")
@RestController
@RequestMapping("/api/v1/loyalty/members")
public class MemberController {

  private final MemberService memberService;

  public MemberController(MemberService memberService) {
    this.memberService = memberService;
  }

  @Operation(
      summary = "Enroll a member",
      description =
          "Enrolls a new loyalty member by phone (normalized server-side). 409 if the phone is"
              + " already enrolled for this tenant.")
  @PostMapping
  public ResponseEntity<MemberResponse> enroll(@Valid @RequestBody EnrollMemberRequest request) {
    MemberResponse created = memberService.enroll(request);
    return ResponseEntity.created(URI.create("/api/v1/loyalty/members/" + created.id()))
        .body(created);
  }

  @Operation(
      summary = "Look up a member by phone",
      description =
          "Exact-match lookup by phone (the POS 'attach an existing member' flow). Returns the"
              + " display name plain + the phone's last 4 digits for cashier confirmation, never"
              + " the full phone. 404 if unknown.")
  @GetMapping
  public MemberResponse lookupByPhone(@RequestParam String phone) {
    return memberService.lookupByPhone(phone);
  }

  @Operation(
      summary = "Get a member by id",
      description = "404 if unknown or belonging to another tenant.")
  @GetMapping("/{id}")
  public MemberResponse getById(@PathVariable UUID id) {
    return memberService.getById(id);
  }
}
