/**
 * channelCode.ts — client-side normalization of a sales-channel code (ADR 0036 Phase B3). The
 * code is UPPERCASE and immutable once created (SalesChannelResponse.code); this mirrors that
 * canonical form live as the owner/manager types it into the create dialog so the preview always
 * matches what the server will store — the server remains the source of truth/validation.
 */

/** Uppercases and strips every character outside A-Z, 0-9, `-`, `_`. */
export function normalizeChannelCode(raw: string): string {
  return raw.toUpperCase().replace(/[^A-Z0-9_-]/g, '')
}
