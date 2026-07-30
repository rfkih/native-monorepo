/**
 * RuleDialog — create/edit a promo_rule (ADR 0026). Fields are TYPE-DEPENDENT:
 *   PERCENT_OFF_ORDER / PERCENT_OFF_LINE — a basis-point rate, shown as a % input.
 *   AMOUNT_OFF_ORDER                     — a fixed amount in the company's base currency.
 *   PERCENT_OFF_LINE                     — additionally requires a scope (ITEM everywhere;
 *                                           CATEGORY is a restaurant-only dimension).
 * ruleType is immutable once created (PromoRulePatchRequest has no ruleType field) — the type
 * picker is only interactive in create mode; edit mode shows it as a fixed badge.
 *
 * Money rule (rule 8): rateBp/amountMinor are integer wire units; the % and major-currency inputs
 * are display-only conversions. Strings rule (rule 9): every label is an i18n key.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { Field, TextInput } from '@/components/ui/Field'
import { Select } from '@/components/ui/Select'
import { Badge } from '@/components/ui/Badge'
import { isoMinorExponent } from '@/lib/money'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import {
  useCreatePromoRule,
  usePatchPromoRule,
  type PromoRuleResponse,
  type PromoRuleType,
  type PromoScopeKind,
  type PromotionVertical,
} from './api'
import { daysToDowMask, dowMaskToDays, fromTimeInputValue, promoRuleErrorKey, toTimeInputValue, weekdayShortLabel } from './format'

export interface ScopeOption {
  id: string
  name: string
}

interface Props {
  session: CompanySession
  vertical: PromotionVertical
  /** null = create mode; a rule = edit mode (ruleType then fixed). */
  rule: PromoRuleResponse | null
  itemOptions: ScopeOption[]
  /** Non-empty only for restaurant (CATEGORY is a restaurant-only scope kind). */
  categoryOptions: ScopeOption[]
  currency: string
  locale: string
  onClose: () => void
}

function toMinor(major: string, currency: string): number | null {
  if (major.trim() === '') return null
  const n = Number(major)
  if (isNaN(n)) return null
  return Math.round(n * 10 ** isoMinorExponent(currency))
}

function toMajorInput(minor: number | null, currency: string): string {
  if (minor == null) return ''
  return String(minor / 10 ** isoMinorExponent(currency))
}

const DAY_INDEXES = [0, 1, 2, 3, 4, 5, 6] as const

export function RuleDialog({ session, vertical, rule, itemOptions, categoryOptions, currency, locale, onClose }: Props) {
  const { t } = useTranslation()
  const isEdit = rule != null
  const createRule = useCreatePromoRule(vertical, session)
  const patchRule = usePatchPromoRule(vertical, session)

  const [name, setName] = useState(rule?.name ?? '')
  const [ruleType, setRuleType] = useState<PromoRuleType>(rule?.ruleType ?? 'PERCENT_OFF_ORDER')
  const [scopeKind, setScopeKind] = useState<PromoScopeKind>(rule?.scopeKind ?? 'ITEM')
  const [scopeRefId, setScopeRefId] = useState(rule?.scopeRefId ?? '')
  const [ratePercent, setRatePercent] = useState(rule?.rateBp != null ? String(rule.rateBp / 100) : '')
  const [amountInput, setAmountInput] = useState(toMajorInput(rule?.amountMinor ?? null, currency))
  const [minSubtotalInput, setMinSubtotalInput] = useState(toMajorInput(rule?.minSubtotalMinor ?? null, currency))
  const [days, setDays] = useState<Set<number>>(() => dowMaskToDays(rule?.dowMask ?? null))
  const [windowStart, setWindowStart] = useState(toTimeInputValue(rule?.windowStart ?? null))
  const [windowEnd, setWindowEnd] = useState(toTimeInputValue(rule?.windowEnd ?? null))
  const [tz, setTz] = useState(rule?.tz ?? 'Asia/Jakarta')
  const [priority, setPriority] = useState(rule ? String(rule.priority) : '100')
  const [exclusive, setExclusive] = useState(rule?.exclusive ?? false)
  const [requiresCoupon, setRequiresCoupon] = useState(rule?.requiresCoupon ?? false)
  const [active, setActive] = useState(rule?.active ?? true)
  const [effectiveFrom, setEffectiveFrom] = useState(rule?.effectiveFrom ?? '')
  const [effectiveTo, setEffectiveTo] = useState(rule?.effectiveTo ?? '')
  const [error, setError] = useState<string | null>(null)

  const isPercent = ruleType === 'PERCENT_OFF_ORDER' || ruleType === 'PERCENT_OFF_LINE'
  const isAmount = ruleType === 'AMOUNT_OFF_ORDER'
  const isLineScope = ruleType === 'PERCENT_OFF_LINE'
  const scopeOptions = scopeKind === 'CATEGORY' ? categoryOptions : itemOptions

  const typeOptions: { value: PromoRuleType; label: string }[] = [
    { value: 'PERCENT_OFF_ORDER', label: t('promotions.ruleType.PERCENT_OFF_ORDER') },
    { value: 'AMOUNT_OFF_ORDER', label: t('promotions.ruleType.AMOUNT_OFF_ORDER') },
    { value: 'PERCENT_OFF_LINE', label: t('promotions.ruleType.PERCENT_OFF_LINE') },
  ]
  const scopeKindOptions: { value: PromoScopeKind; label: string }[] = [
    { value: 'ITEM', label: t('promotions.scopeKind.ITEM') },
    { value: 'CATEGORY', label: t('promotions.scopeKind.CATEGORY') },
  ]

  function toggleDay(d: number) {
    setDays((prev) => {
      const next = new Set(prev)
      if (next.has(d)) next.delete(d)
      else next.add(d)
      return next
    })
  }

  function validate(): string | null {
    if (!name.trim()) return t('promotions.errors.nameRequired')
    if (isPercent && ratePercent.trim() === '') return t('promotions.errors.rateRequired')
    if (isAmount && amountInput.trim() === '') return t('promotions.errors.amountRequired')
    if (isLineScope && !scopeRefId) return t('promotions.errors.scopeRequired')
    return null
  }

  async function submit() {
    setError(null)
    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }
    const dowMask = daysToDowMask(days)
    const common = {
      name: name.trim(),
      scopeKind: isLineScope ? scopeKind : null,
      scopeRefId: isLineScope ? scopeRefId : null,
      rateBp: isPercent ? Math.round(Number(ratePercent) * 100) : null,
      amountMinor: isAmount ? toMinor(amountInput, currency) : null,
      currency: isAmount ? currency : null,
      minSubtotalMinor: toMinor(minSubtotalInput, currency),
      dowMask,
      windowStart: fromTimeInputValue(windowStart),
      windowEnd: fromTimeInputValue(windowEnd),
      tz: tz.trim() || 'Asia/Jakarta',
      priority: priority.trim() === '' ? null : Number(priority),
      exclusive,
      requiresCoupon,
      effectiveFrom: effectiveFrom || null,
      effectiveTo: effectiveTo || null,
    }
    try {
      if (isEdit && rule) {
        await patchRule.mutateAsync({ id: rule.id, ...common, active })
      } else {
        await createRule.mutateAsync({ ruleType, ...common })
      }
      onClose()
    } catch (e) {
      setError(t(promoRuleErrorKey(e)))
    }
  }

  const isPending = createRule.isPending || patchRule.isPending

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={isEdit ? t('promotions.editRule') : t('promotions.newRule')}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <Card className="max-h-[90vh] w-full max-w-lg overflow-y-auto p-6">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="font-display text-xl font-semibold text-ink">
            {isEdit ? t('promotions.editRule') : t('promotions.newRule')}
          </h2>
          <button
            type="button"
            aria-label={t('common.close')}
            onClick={onClose}
            className="text-ink-3 hover:text-ink"
          >
            <X className="size-5" />
          </button>
        </div>

        <div className="flex flex-col gap-3.5">
          <Field label={t('promotions.fields.name')} htmlFor="rule-name">
            <TextInput id="rule-name" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>

          <div>
            <span className="mb-1.5 block text-sm font-medium text-ink">{t('promotions.fields.ruleType')}</span>
            {isEdit ? (
              <Badge tone="neutral">{t(`promotions.ruleType.${ruleType}`)}</Badge>
            ) : (
              <Segmented options={typeOptions} value={ruleType} onChange={setRuleType} ariaLabel={t('promotions.fields.ruleType')} />
            )}
          </div>

          {isPercent ? (
            <Field label={t('promotions.fields.rate')} htmlFor="rule-rate" hint={t('promotions.fields.rateHint')}>
              <div className="relative">
                <TextInput
                  id="rule-rate"
                  type="number"
                  min="0"
                  max="100"
                  step="0.01"
                  value={ratePercent}
                  onChange={(e) => setRatePercent(e.target.value)}
                  className="pr-9"
                />
                <span className="pointer-events-none absolute right-4 top-1/2 -translate-y-1/2 text-sm text-ink-3">%</span>
              </div>
            </Field>
          ) : null}

          {isAmount ? (
            <Field label={t('promotions.fields.amount', { currency })} htmlFor="rule-amount">
              <TextInput
                id="rule-amount"
                type="number"
                min="0"
                step="any"
                value={amountInput}
                onChange={(e) => setAmountInput(e.target.value)}
              />
            </Field>
          ) : null}

          {isLineScope ? (
            <>
              {categoryOptions.length > 0 ? (
                <div>
                  <span className="mb-1.5 block text-sm font-medium text-ink">{t('promotions.fields.scopeKind')}</span>
                  <Segmented
                    options={scopeKindOptions}
                    value={scopeKind}
                    onChange={(v) => {
                      setScopeKind(v)
                      setScopeRefId('')
                    }}
                    ariaLabel={t('promotions.fields.scopeKind')}
                  />
                </div>
              ) : null}
              <Field label={t('promotions.fields.scopeRef')} htmlFor="rule-scope-ref">
                <Select
                  id="rule-scope-ref"
                  value={scopeRefId}
                  onChange={(e) => setScopeRefId(e.target.value)}
                >
                  <option value="">{t('promotions.fields.selectScopeRef')}</option>
                  {scopeOptions.map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.name}
                    </option>
                  ))}
                </Select>
              </Field>
            </>
          ) : null}

          <Field
            label={t('promotions.fields.minSubtotal', { currency })}
            htmlFor="rule-min-subtotal"
            hint={t('promotions.fields.minSubtotalHint')}
          >
            <TextInput
              id="rule-min-subtotal"
              type="number"
              min="0"
              step="any"
              value={minSubtotalInput}
              onChange={(e) => setMinSubtotalInput(e.target.value)}
            />
          </Field>

          <div className="rounded-2xl border border-line bg-paper p-4">
            <span className="mb-2 block text-sm font-semibold text-ink">{t('promotions.fields.happyHour')}</span>
            <p className="mb-3 text-xs text-ink-3">{t('promotions.fields.happyHourHint')}</p>
            <div className="mb-3 flex flex-wrap gap-1.5">
              {DAY_INDEXES.map((d) => {
                const on = days.has(d)
                return (
                  <button
                    key={d}
                    type="button"
                    aria-pressed={on}
                    onClick={() => toggleDay(d)}
                    className={cn(
                      'h-9 min-w-9 rounded-lg border-[1.5px] px-2.5 text-[13px] font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                      on
                        ? 'border-emerald bg-emerald text-on-emerald'
                        : 'border-line bg-surface text-ink-2 hover:border-emerald-line hover:bg-emerald-tint',
                    )}
                  >
                    {weekdayShortLabel(d, locale)}
                  </button>
                )
              })}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Field label={t('promotions.fields.windowStart')} htmlFor="rule-window-start">
                <TextInput
                  id="rule-window-start"
                  type="time"
                  value={windowStart}
                  onChange={(e) => setWindowStart(e.target.value)}
                />
              </Field>
              <Field label={t('promotions.fields.windowEnd')} htmlFor="rule-window-end">
                <TextInput
                  id="rule-window-end"
                  type="time"
                  value={windowEnd}
                  onChange={(e) => setWindowEnd(e.target.value)}
                />
              </Field>
            </div>
            <Field label={t('promotions.fields.tz')} htmlFor="rule-tz" hint={t('promotions.fields.tzHint')}>
              <TextInput id="rule-tz" value={tz} onChange={(e) => setTz(e.target.value)} />
            </Field>
          </div>

          <Field label={t('promotions.fields.priority')} htmlFor="rule-priority" hint={t('promotions.fields.priorityHint')}>
            <TextInput
              id="rule-priority"
              type="number"
              min="0"
              step="1"
              value={priority}
              onChange={(e) => setPriority(e.target.value)}
            />
          </Field>

          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <label className="flex cursor-pointer items-start gap-2.5 rounded-xl border border-line bg-surface p-3">
              <input
                type="checkbox"
                checked={exclusive}
                onChange={(e) => setExclusive(e.target.checked)}
                className="mt-0.5 size-4 accent-emerald"
              />
              <span>
                <span className="block text-sm font-medium text-ink">{t('promotions.fields.exclusive')}</span>
                <span className="mt-0.5 block text-xs text-ink-3">{t('promotions.fields.exclusiveHint')}</span>
              </span>
            </label>
            <label className="flex cursor-pointer items-start gap-2.5 rounded-xl border border-line bg-surface p-3">
              <input
                type="checkbox"
                checked={requiresCoupon}
                onChange={(e) => setRequiresCoupon(e.target.checked)}
                className="mt-0.5 size-4 accent-emerald"
              />
              <span>
                <span className="block text-sm font-medium text-ink">{t('promotions.fields.requiresCoupon')}</span>
                <span className="mt-0.5 block text-xs text-ink-3">{t('promotions.fields.requiresCouponHint')}</span>
              </span>
            </label>
          </div>

          {isEdit ? (
            <label className="flex cursor-pointer items-center gap-2.5 rounded-xl border border-line bg-surface p-3">
              <input
                type="checkbox"
                checked={active}
                onChange={(e) => setActive(e.target.checked)}
                className="size-4 accent-emerald"
              />
              <span className="text-sm font-medium text-ink">{t('promotions.fields.active')}</span>
            </label>
          ) : null}

          <div className="grid grid-cols-2 gap-3">
            <Field label={t('promotions.fields.effectiveFrom')} htmlFor="rule-effective-from">
              <TextInput
                id="rule-effective-from"
                type="date"
                value={effectiveFrom}
                onChange={(e) => setEffectiveFrom(e.target.value)}
              />
            </Field>
            <Field label={t('promotions.fields.effectiveTo')} htmlFor="rule-effective-to">
              <TextInput
                id="rule-effective-to"
                type="date"
                value={effectiveTo}
                onChange={(e) => setEffectiveTo(e.target.value)}
              />
            </Field>
          </div>

          {error ? (
            <div className="flex items-center gap-2 text-sm text-loss" role="alert">
              <TriangleAlert className="size-4 shrink-0" aria-hidden />
              {error}
            </div>
          ) : null}

          <div className="mt-1 flex justify-end gap-2.5">
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="button" onClick={submit} disabled={isPending}>
              {isPending ? <Spinner /> : isEdit ? t('promotions.save') : t('promotions.create')}
            </Button>
          </div>
        </div>
      </Card>
    </div>
  )
}
