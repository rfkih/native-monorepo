/**
 * suggestedChannels.ts — the fixed "suggested standard delivery platforms" list (GoFood /
 * GrabFood / ShopeeFood) rendered as one-tap quick-add chips on the Channels roster page
 * (Channels.tsx). This is a SUGGESTION only: nothing here auto-creates a channel or seeds on
 * load — the product also serves carwash/barbershop tenants where delivery channels are
 * irrelevant, so the owner opts in per platform with a tap, which goes through the SAME
 * useCreateSalesChannel mutation the manual "New channel" dialog uses (see channelsApi.ts).
 */

/** A suggested platform's fixed code+name — shape mirrors CreateSalesChannelBody. */
export interface SuggestedPlatform {
  code: string
  name: string
}

/** The three standard Indonesian food-delivery platforms — display names are proper nouns, not translated. */
export const STANDARD_DELIVERY_PLATFORMS: SuggestedPlatform[] = [
  { code: 'GOFOOD', name: 'GoFood' },
  { code: 'GRABFOOD', name: 'GrabFood' },
  { code: 'SHOPEEFOOD', name: 'ShopeeFood' },
]

/**
 * Suggestions not yet present in the company's channel roster — compares codes
 * case-insensitively against `existingCodes` (a channel code is stored/normalized uppercase —
 * see channelCode.ts — but this stays defensive of that). Once every standard platform already
 * exists, this returns `[]` and the whole suggestions row disappears (Channels.tsx).
 */
export function availableSuggestions(
  existingCodes: readonly string[],
  suggestions: readonly SuggestedPlatform[] = STANDARD_DELIVERY_PLATFORMS,
): SuggestedPlatform[] {
  const existing = new Set(existingCodes.map((code) => code.toUpperCase()))
  return suggestions.filter((platform) => !existing.has(platform.code.toUpperCase()))
}
