/**
 * config.ts — the per-vertical wiring a generic "service POS" surface needs to become a concrete
 * terminal (bay/chair-style verticals that sell a package + optional add-ons, as opposed to the
 * item-grid restaurant POS in features/pos/). Carwash is the first vertical; the shape is written
 * so a future vertical (e.g. barbershop) only needs a new VerticalPosConfig + api base, never a
 * change to ServicePos.tsx / ServicePaymentModal.tsx / ServiceReceipt.tsx.
 *
 * Rules: no hardcoded user-facing strings (rule 9) — every label lives behind an i18n key, so this
 * file carries KEYS, never literal copy.
 */

import type { Vertical } from '@/features/org/api'

/** The two catalog line kinds a service ticket can carry. */
export type CatalogItemKind = 'PACKAGE' | 'ADDON'

/** The three tender types every POS payment flow supports (mirrors the restaurant POS, ADR 0006). */
export type TenderType = 'CASH' | 'QRIS' | 'CARD'

export interface VerticalPosConfig {
  /** The business-unit vertical this config drives — must match OutletGate's requiredVertical. */
  vertical: Vertical
  /** REST path prefix for this vertical's service-POS endpoints, e.g. '/api/v1/carwash'. */
  apiBase: string
  /** i18n key prefix for this vertical's own copy (bay/washer labels, etc.), e.g. 'carwashPos'. */
  i18nNs: string
  /** The "where" field on a ticket (bay for carwash, chair for a future barbershop vertical). */
  location: {
    /** i18n key for the field label. */
    labelKey: string
    required: boolean
  }
  /** Whether this vertical's ticket carries a vehicle-plate field (carwash: yes). */
  vehicleField: boolean
  /** The "who serviced this" field (washer for carwash) — drives own-sales commission attribution. */
  attribution: {
    enabled: boolean
    required: boolean
    /** i18n key for the field label. */
    labelKey: string
  }
}
