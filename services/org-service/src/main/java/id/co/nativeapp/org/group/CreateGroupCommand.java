package id.co.nativeapp.org.group;

/**
 * The application command to create a consolidation group. The lead company is the bound tenant
 * (resolved from {@link id.co.nativeapp.tenant.TenantContext} by the writer), never carried on this
 * command (rule 5).
 *
 * @param name the group name
 * @param reportingCurrency the ISO-4217 reporting currency (validated + made immutable by {@link
 *     ConsolidationGroup})
 */
public record CreateGroupCommand(String name, String reportingCurrency) {}
