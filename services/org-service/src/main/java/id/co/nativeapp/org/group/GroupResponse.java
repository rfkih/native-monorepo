package id.co.nativeapp.org.group;

import java.util.UUID;

/**
 * Consolidation-group response body — the group just created. Exposes its identity, lead, name, and
 * immutable reporting currency so the console can render it without a second call.
 *
 * @param id the group id
 * @param leadCompanyId the lead/owner company (also the group's tenant)
 * @param name the group name
 * @param reportingCurrency the immutable ISO-4217 reporting currency
 */
public record GroupResponse(UUID id, UUID leadCompanyId, String name, String reportingCurrency) {

  static GroupResponse from(ConsolidationGroup group) {
    return new GroupResponse(
        group.getId(), group.getLeadCompanyId(), group.getName(), group.getReportingCurrency());
  }
}
