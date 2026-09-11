/**
 * QtyKeypadSheet — Terima (a signed delta) and Atur jumlah (an absolute figure) on one sheet with
 * its own pad, the inventory twin of the opname count sheet. A `DialogOverlay` (bottom sheet on the
 * phone, centred on desktop — ADR 0075 N3): title over "sekarang 8,4 kg", a Tambah / Koreksi
 * kurang toggle on a receive, the figure in the SHOWN unit with its sign, a live "8,4 → 12,4 kg"
 * preview, the pad, and Batal / submit. A physical keyboard goes through the same reducer as the
 * pad (`lib/countKeypad`), `inputMode="none"` keeps the on-screen one down.
 *
 * ADR 0072 §5 — a receive here is purely a quantity. A purchase WITH a payment is recorded once,
 * in full, via the company-expense form; the hint pointing there is FINANCE-gated by the caller.
 * Money rule (rule 8): nothing here is a price. Rule 9: every string is a key.
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Info, X } from 'lucide-react'
import { DialogOverlay } from '@/components/ui/Dialog'
import { Button } from '@/components/ui/Button'
import { Segmented } from '@/components/ui/Segmented'
import { Spinner } from '@/components/ui/Spinner'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import { QtyKeypad } from './QtyKeypad'
import { useAddIngredientStock, useSetIngredientStock, type Ingredient } from './ingredientApi'
import {
  applyCountKey,
  countDraftWithinCap,
  decimalSeparatorOf,
  type CountKey,
} from './lib/countKeypad'
import { previewReceive, previewSet, type ReceiveSign } from './lib/qtyDraft'
import {
  allowsFraction,
  formatShownQty,
  formatSignedShownQty,
  sanitizeShownQtyInput,
  shownQtyInputValue,
  shownUnit,
} from './lib/units'

export type KeypadMode = 'receive' | 'set'

const ICON_BUTTON =
  'grid size-11 shrink-0 place-items-center rounded-xl text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald'

/** Fixed bottom surfaces bypass the body's safe-area padding (index.css) — each pads itself. */
const SAFE_BOTTOM = (px: number) => ({
  paddingBottom: `calc(${px}px + var(--safe-area-inset-bottom, 0px))`,
})

export function QtyKeypadSheet({
  session,
  ingredient,
  mode,
  locale,
  financeOk,
  onClose,
}: {
  session: CompanySession
  ingredient: Ingredient
  mode: KeypadMode
  locale: string
  /** Whether the caller may see the "record it as an expense" hint (owner/accountant). */
  financeOk: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const add = useAddIngredientStock(session)
  const set = useSetIngredientStock(session)
  const inputRef = useRef<HTMLInputElement>(null)

  const fraction = allowsFraction(ingredient)
  const unit = shownUnit(ingredient)
  const separator = decimalSeparatorOf(locale)
  const current = formatShownQty(ingredient.stockQty, ingredient, locale)

  const [sign, setSign] = useState<ReceiveSign>('add')
  // A set starts from the figure on record and the first key REPLACES it (select-on-focus
  // semantics); a receive starts empty.
  const [raw, setRaw] = useState(() =>
    mode === 'set' ? shownQtyInputValue(ingredient.stockQty, ingredient, locale) : '',
  )
  const [pristine, setPristine] = useState(mode === 'set')

  const receive =
    mode === 'receive' ? previewReceive(raw, sign, ingredient.stockQty, ingredient) : null
  const setPrev = mode === 'set' ? previewSet(raw, ingredient.stockQty, ingredient) : null
  const ok = mode === 'receive' ? receive?.ok === true : setPrev?.ok === true
  const busy = add.isPending || set.isPending
  const failed = add.isError || set.isError

  // DialogOverlay focuses its panel in a parent effect, which runs AFTER this child's — take the
  // focus one frame later so a physical keyboard types straight into the figure.
  useEffect(() => {
    const id = requestAnimationFrame(() => inputRef.current?.focus())
    return () => cancelAnimationFrame(id)
  }, [])

  const press = (key: CountKey) => {
    setRaw(applyCountKey(pristine ? '' : raw, key, fraction))
    setPristine(false)
  }

  const submit = (done: () => void) => {
    if (!ok || busy) return
    if (mode === 'receive' && receive?.ok) {
      // ADR 0072 §5 — always the costless path (no price input here — see the module doc).
      add.mutate({ id: ingredient.id, amount: receive.deltaBase }, { onSuccess: done })
    } else if (mode === 'set' && setPrev?.ok) {
      set.mutate({ id: ingredient.id, quantity: setPrev.afterBase }, { onSuccess: done })
    }
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>, done: () => void) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      submit(done)
      return
    }
    if (e.ctrlKey || e.metaKey || e.altKey) return
    if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(e.key)) {
      e.preventDefault()
      return
    }
    const key: CountKey | null =
      e.key === 'Backspace' || e.key === 'Delete'
        ? 'backspace'
        : /^[0-9.,]$/.test(e.key)
          ? (e.key as CountKey)
          : null
    if (key == null) return
    e.preventDefault()
    press(key)
  }

  const onPaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault()
    const next = sanitizeShownQtyInput(
      pristine ? e.clipboardData.getData('text') : raw + e.clipboardData.getData('text'),
    )
    if (!countDraftWithinCap(next, fraction)) return
    setRaw(next)
    setPristine(false)
  }

  const title =
    mode === 'set'
      ? t('inventory.keypadSheet.setTitle')
      : sign === 'remove'
        ? t('inventory.keypadSheet.correctTitle')
        : t('inventory.keypadSheet.receiveTitle')
  const submitLabel =
    mode === 'set'
      ? t('inventory.keypadSheet.submitSet')
      : sign === 'remove'
        ? t('inventory.keypadSheet.submitCorrect')
        : t('inventory.keypadSheet.submitReceive')

  // The preview is the whole safety mechanism: a mistyped figure is invisible as a number and
  // obvious as a resulting shelf.
  let preview: { text: string; tone: 'faint' | 'ink' | 'warn' }
  if (!ok) {
    preview = { text: t('inventory.keypadSheet.typeAmount'), tone: 'faint' }
  } else if (mode === 'receive' && receive?.ok) {
    preview = receive.clipped
      ? { text: t('inventory.keypadSheet.receiveClipped', { before: current, unit }), tone: 'warn' }
      : {
          text: t('inventory.keypadSheet.receivePreview', {
            before: current,
            after: formatShownQty(receive.afterBase, ingredient, locale),
            unit,
          }),
          tone: receive.deltaBase < 0 ? 'warn' : 'ink',
        }
  } else if (setPrev?.ok) {
    preview =
      setPrev.diffBase === 0
        ? { text: t('inventory.keypadSheet.setSame'), tone: 'faint' }
        : {
            text: t('inventory.keypadSheet.setPreview', {
              diff: formatSignedShownQty(setPrev.diffBase, ingredient, locale),
              unit,
            }),
            tone: setPrev.diffBase < 0 ? 'warn' : 'ink',
          }
  } else {
    preview = { text: '', tone: 'faint' }
  }

  const signPrefix = mode === 'receive' && raw !== '' ? (sign === 'remove' ? '−' : '+') : ''
  const showFinanceHint = mode === 'receive' && sign === 'add' && ok && financeOk

  return (
    <DialogOverlay onClose={onClose} ariaLabel={`${title} — ${ingredient.name}`} className="p-0">
      {(requestClose) => {
        const done = () => requestClose()
        return (
          <div className="flex flex-col" data-testid="inventory-keypad-sheet">
            <div className="flex shrink-0 justify-center pb-1 pt-2.5 sm:hidden" aria-hidden="true">
              <div className="h-1 w-10 rounded-full bg-ink-300" />
            </div>
            <div className="flex shrink-0 items-start gap-2 px-[18px] pt-1.5 sm:pt-4">
              <div className="min-w-0 flex-1 pt-1">
                <div className="truncate text-base font-bold leading-tight text-ink">{title}</div>
                <div className="tnum mt-1 truncate font-mono text-xs text-ink-3">
                  {ingredient.name} · {t('inventory.keypadSheet.current', { qty: current, unit })}
                </div>
              </div>
              <button
                type="button"
                onClick={requestClose}
                aria-label={t('common.close')}
                className={cn(ICON_BUTTON, '-mr-2.5')}
              >
                <X className="size-[19px]" aria-hidden="true" />
              </button>
            </div>

            {mode === 'receive' ? (
              <div className="px-[18px] pt-3.5">
                <Segmented<ReceiveSign>
                  fluid
                  ariaLabel={title}
                  value={sign}
                  onChange={setSign}
                  options={[
                    { value: 'add', label: t('inventory.keypadSheet.signAdd') },
                    { value: 'remove', label: t('inventory.keypadSheet.signRemove') },
                  ]}
                />
              </div>
            ) : null}

            <div className="flex items-baseline justify-center gap-1.5 px-[18px] pt-[18px]">
              <span
                aria-hidden="true"
                className={cn(
                  'tnum font-mono text-5xl font-bold leading-none tracking-[-0.03em]',
                  raw === ''
                    ? 'text-ink-400'
                    : sign === 'remove' && mode === 'receive'
                      ? 'text-loss'
                      : 'text-ink',
                )}
              >
                {signPrefix}
              </span>
              <input
                ref={inputRef}
                type="text"
                inputMode="none"
                autoComplete="off"
                spellCheck={false}
                value={raw}
                placeholder="0"
                aria-label={t('inventory.keypadSheet.figureAria', { name: ingredient.name, unit })}
                onChange={(e) => {
                  const next = sanitizeShownQtyInput(e.target.value)
                  if (!countDraftWithinCap(next, fraction)) return
                  setRaw(next)
                  setPristine(false)
                }}
                onKeyDown={(e) => onKeyDown(e, done)}
                onPaste={onPaste}
                style={{ width: `${Math.max(1, raw.length)}ch` }}
                className={cn(
                  'tnum min-w-[1ch] bg-transparent text-right font-mono text-5xl font-bold leading-none tracking-[-0.03em] caret-transparent placeholder:text-ink-400 focus:outline-none',
                  sign === 'remove' && mode === 'receive' ? 'text-loss' : 'text-ink',
                )}
              />
              <span className="text-base font-semibold text-ink-3">{unit}</span>
            </div>

            <div
              className={cn(
                'min-h-[34px] px-[18px] pt-3 text-center text-xs font-semibold',
                preview.tone === 'faint'
                  ? 'text-ink-400'
                  : preview.tone === 'warn'
                    ? 'text-amber'
                    : 'text-ink-2',
              )}
              aria-live="polite"
            >
              {preview.text}
            </div>

            {showFinanceHint ? (
              <div className="mx-[18px] mt-2 flex items-start gap-2.5 rounded-[14px] bg-tint-info px-3.5 py-[13px]">
                <Info className="mt-0.5 size-4 shrink-0 text-info" aria-hidden="true" />
                <span className="flex-1 text-xs leading-relaxed text-ink-2">
                  {t('inventory.keypadSheet.financeHint')}{' '}
                  <Link
                    to="/expenses/record"
                    className="font-bold text-profit-ink no-underline hover:underline"
                  >
                    {t('inventory.keypadSheet.financeLink')}
                  </Link>{' '}
                  {t('inventory.keypadSheet.financeHintTail')}
                </span>
              </div>
            ) : null}

            {failed ? (
              <p className="px-[18px] pt-2 text-center text-xs text-loss" role="alert">
                {t('inventory.errorGeneric')}
              </p>
            ) : null}

            <div className="pt-4">
              <QtyKeypad fraction={fraction} separator={separator} onKey={press} />
            </div>

            <div className="flex shrink-0 gap-2 px-[18px] pt-3.5" style={SAFE_BOTTOM(24)}>
              <Button
                variant="outline"
                size="2xl"
                className="shrink-0 px-5"
                onClick={requestClose}
                disabled={busy}
              >
                {t('common.cancel')}
              </Button>
              <Button
                size="2xl"
                className="flex-1"
                data-testid="inventory-keypad-submit"
                disabled={!ok || busy}
                onClick={() => submit(done)}
              >
                {busy ? <Spinner /> : submitLabel}
              </Button>
            </div>
          </div>
        )
      }}
    </DialogOverlay>
  )
}
