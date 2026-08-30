/**
 * CouponDialog — create/edit a coupon (ADR 0026). The code is normalized to uppercase (mirrors the
 * backend's own trim+uppercase, done here too so the on-screen preview matches what will be saved).
 * code/ruleId are immutable once created (CouponPatchRequest has neither field) — both are fixed in
 * edit mode.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X, TriangleAlert } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import { Select } from '@/components/ui/Select'
import type { CompanySession } from '@/lib/session'
import {
  useCreateCoupon,
  usePatchCoupon,
  type CouponResponse,
  type PromoRuleResponse,
  type PromotionVertical,
} from './api'
import { couponErrorKey } from './format'

interface Props {
  session: CompanySession
  vertical: PromotionVertical
  /** null = create mode. */
  coupon: CouponResponse | null
  rules: PromoRuleResponse[]
  onClose: () => void
}

/** 'YYYY-MM-DDTHH:mm' (the <input type="datetime-local"> value) <-> an ISO instant. */
function toDatetimeLocalValue(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function fromDatetimeLocalValue(value: string): string | null {
  if (!value) return null
  const d = new Date(value)
  if (isNaN(d.getTime())) return null
  return d.toISOString()
}

export function CouponDialog({ session, vertical, coupon, rules, onClose }: Props) {
  useBackDismiss(onClose)
  const { t } = useTranslation()
  const isEdit = coupon != null
  const createCoupon = useCreateCoupon(vertical, session)
  const patchCoupon = usePatchCoupon(vertical, session)

  const [code, setCode] = useState(coupon?.code ?? '')
  const [ruleId, setRuleId] = useState(coupon?.ruleId ?? '')
  const [maxRedemptions, setMaxRedemptions] = useState(coupon ? String(coupon.maxRedemptions) : '1')
  const [expiresAt, setExpiresAt] = useState(toDatetimeLocalValue(coupon?.expiresAt ?? null))
  const [active, setActive] = useState(coupon?.active ?? true)
  const [error, setError] = useState<string | null>(null)

  const isPending = createCoupon.isPending || patchCoupon.isPending

  async function submit() {
    setError(null)
    if (!isEdit) {
      if (!code.trim()) {
        setError(t('promotions.errors.codeRequired'))
        return
      }
      if (!ruleId) {
        setError(t('promotions.errors.ruleRequired'))
        return
      }
    }
    const maxN = maxRedemptions.trim() === '' ? null : Number(maxRedemptions)
    if (maxN != null && maxN <= 0) {
      setError(t('promotions.errors.maxRedemptionsInvalid'))
      return
    }
    try {
      if (isEdit && coupon) {
        await patchCoupon.mutateAsync({
          id: coupon.id,
          maxRedemptions: maxN,
          expiresAt: fromDatetimeLocalValue(expiresAt),
          active,
        })
      } else {
        await createCoupon.mutateAsync({
          code: code.trim().toUpperCase(),
          ruleId,
          maxRedemptions: maxN,
          expiresAt: fromDatetimeLocalValue(expiresAt),
        })
      }
      onClose()
    } catch (e) {
      setError(t(couponErrorKey(e)))
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={isEdit ? t('promotions.editCoupon') : t('promotions.newCoupon')}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <Card className="w-full max-w-md p-6">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="font-display text-xl font-semibold text-ink">
            {isEdit ? t('promotions.editCoupon') : t('promotions.newCoupon')}
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
          <Field label={t('promotions.fields.code')} htmlFor="coupon-code" hint={t('promotions.fields.codeHint')}>
            <TextInput
              id="coupon-code"
              value={code}
              disabled={isEdit}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              className="font-mono uppercase tracking-wider"
            />
          </Field>

          <Field label={t('promotions.fields.linkedRule')} htmlFor="coupon-rule">
            <Select
              id="coupon-rule"
              value={ruleId}
              disabled={isEdit}
              onChange={(e) => setRuleId(e.target.value)}
            >
              <option value="">{t('promotions.fields.selectRule')}</option>
              {rules.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </Select>
          </Field>

          <Field label={t('promotions.fields.maxRedemptions')} htmlFor="coupon-max">
            <TextInput
              id="coupon-max"
              type="number"
              min="1"
              step="1"
              value={maxRedemptions}
              onChange={(e) => setMaxRedemptions(e.target.value)}
            />
          </Field>

          <Field
            label={t('promotions.fields.expiresAt')}
            htmlFor="coupon-expires"
            hint={t('promotions.fields.expiresAtHint')}
          >
            <TextInput
              id="coupon-expires"
              type="datetime-local"
              value={expiresAt}
              onChange={(e) => setExpiresAt(e.target.value)}
            />
          </Field>

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
