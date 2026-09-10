/**
 * qtyDraft.ts — what the receive / set-quantity keypad is about to do, from the draft it holds.
 *
 * The draft is the SHOWN-unit string the pad builds (`countKeypad.ts`); this parses it with the
 * same rules the old dialogs used (`parseShownQtyInput`: either separator, no fraction on a whole
 * unit) and previews the BASE quantity the server will hold afterwards, so the sheet can write
 * "8,4 → 12,4 kg" before anything is committed. The server floors a receive at zero; the preview
 * floors the same way and says so (`clipped`), rather than promising a negative shelf.
 */
import { parseShownQtyInput, type UnitBearing } from './units'

export type ReceiveSign = 'add' | 'remove'

export type ReceivePreview =
  { ok: false } | { ok: true; deltaBase: number; afterBase: number; clipped: boolean }

export type SetPreview = { ok: false } | { ok: true; afterBase: number; diffBase: number }

/**
 * A receive (or a "koreksi kurang") must move something: zero is refused, like the old dialog.
 * The sign is the sheet's toggle, never a typed minus — the pad has no minus key.
 */
export function previewReceive(
  draft: string,
  sign: ReceiveSign,
  stockQty: number,
  ing: UnitBearing,
): ReceivePreview {
  const parsed = parseShownQtyInput(draft, ing)
  if (parsed == null || parsed <= 0) return { ok: false }
  const deltaBase = sign === 'remove' ? -parsed : parsed
  const raw = stockQty + deltaBase
  return { ok: true, deltaBase, afterBase: Math.max(0, raw), clipped: raw < 0 }
}

/** An absolute set: zero is a legitimate answer ("we are out"). */
export function previewSet(draft: string, stockQty: number, ing: UnitBearing): SetPreview {
  const parsed = parseShownQtyInput(draft, ing)
  if (parsed == null) return { ok: false }
  return { ok: true, afterBase: parsed, diffBase: parsed - stockQty }
}
