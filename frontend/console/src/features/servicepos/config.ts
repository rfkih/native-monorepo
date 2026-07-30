/**
 * config.ts — the per-vertical wiring a generic "service POS" surface needs to become a concrete
 * terminal (bay/chair-style verticals that sell a primary item + optional add-ons, as opposed to
 * the item-grid restaurant POS in features/pos/). Carwash was the first vertical (sells
 * "packages"); barbershop is the second (sells "services", ADR-tracked POS-parity Phase 2) — a
 * future vertical only needs a new VerticalPosConfig + api base, never a structural change to
 * ServicePos.tsx / ServicePaymentModal.tsx / ServiceReceipt.tsx (only their copy/path LOOKUPS are
 * config-driven, per the fields below).
 *
 * Rules: no hardcoded user-facing strings (rule 9) — every label lives behind an i18n key, so this
 * file carries KEYS, never literal copy.
 */

import type { Vertical } from '@/features/org/api'

/**
 * The catalog line kinds a service ticket can carry: PACKAGE (carwash's bundled wash) or SERVICE
 * (barbershop's à la carte service) as the primary item, plus ADDON. Which one a vertical's
 * primary item uses is `VerticalPosConfig.primaryItemType`, never hardcoded in ServicePos.tsx.
 */
export type CatalogItemKind = 'PACKAGE' | 'SERVICE' | 'ADDON'

/** The three tender types every POS payment flow supports (mirrors the restaurant POS, ADR 0006). */
export type TenderType = 'CASH' | 'QRIS' | 'CARD'

/** i18n keys for a section that names/lists/adds/edits ONE vertical's primary sellable item — used
 * by both the POS terminal (ServicePos) and the back-office catalog manager (CatalogManagement) so
 * the two surfaces never disagree on what to call the same data ("Packages" for carwash, "Services"
 * for barbershop). */
export interface PrimaryItemLabels {
  /** Section heading, e.g. "Packages" / "Services". */
  titleKey: string
  /** Empty-state message. */
  emptyKey: string
  /** aria-label when the item is NOT selected (terminal only), interpolates {{name}}. */
  selectLabelKey: string
  /** aria-label when the item IS selected (terminal only), interpolates {{name}}. */
  selectedLabelKey: string
  /** "Add package" / "Add service" button (back office). */
  addLabelKey: string
  /** "Edit package" / "Edit service" dialog title (back office). */
  editLabelKey: string
}

export interface VerticalPosConfig {
  /** The business-unit vertical this config drives — must match OutletGate's requiredVertical. */
  vertical: Vertical
  /** REST path prefix for this vertical's service-POS endpoints, e.g. '/api/v1/carwash'. */
  apiBase: string
  /** i18n key prefix for this vertical's own copy (bay/washer labels, addons, etc.), e.g.
   * 'carwashPos'. Vertical-neutral copy under this namespace (addons/manageCatalog) is looked up
   * as `${i18nNs}.<suffix>` so every vertical carries its own full key set, even where the text
   * happens to read the same today. */
  i18nNs: string
  /** The "where" field on a ticket (bay for carwash, chair for barbershop). */
  location: {
    /**
     * The WIRE field name this vertical's backend uses for the location on
     * CheckoutRequest/TicketResponse — 'bay' (carwash) or 'chair' (barbershop). The UI keeps one
     * internal name; api.ts translates at the request/response boundary.
     */
    fieldName: 'bay' | 'chair'
    /** i18n key for the field label. */
    labelKey: string
    /** i18n key for the input placeholder. */
    placeholderKey: string
    required: boolean
  }
  /** Whether this vertical's ticket carries a vehicle-plate field (carwash: yes; barbershop: no). */
  vehicleField: boolean
  /** The "who serviced this" field (washer for carwash, barber for barbershop) — drives own-sales
   * commission attribution AND, when required, the checkout gate (rule: a required attribution
   * blocks Charge until a staff profile is selected — barbershop's is REQUIRED, carwash's is not). */
  attribution: {
    enabled: boolean
    required: boolean
    /** i18n key for the field label. */
    labelKey: string
    /** i18n key for the explanatory hint shown under the picker while required (optional — only
     * meaningful when `required` is true). */
    requiredHintKey?: string
  }
  /** REST path segment for the primary sellable catalog resource — 'packages' for carwash,
   * 'services' for barbershop. Threaded into every packages-shaped endpoint (list/create/update) in
   * api.ts so the wire contract matches the backend without forking the hooks. */
  packagesPath: string
  /** The TicketLineInput/TicketLineResponse `itemType` this vertical's primary item sends/receives
   * — 'PACKAGE' for carwash, 'SERVICE' for barbershop. */
  primaryItemType: CatalogItemKind
  /** This vertical's primary-item copy (see {@link PrimaryItemLabels}). */
  primaryItemLabels: PrimaryItemLabels
  /** i18n key for the CatalogManagement staff-profile section title — "Washers" for carwash,
   * "Barbers" for barbershop (the rest of serviceCatalog.* stays shared/vertical-neutral copy). */
  staffTitleKey: string
}
