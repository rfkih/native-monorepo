/**
 * CheckoutErrorText — the shared checkout/pay error line (redesign P3). Resolution order:
 * insufficient-stock (interpolated) → the unified checkoutErrorKey mapping (outlet assignment,
 * module entitlement, coupon, loyalty, gift card) → the raw error message. Behavior-identical
 * per surface: bills never emit coupon/module/loyalty faults and services never emit
 * insufficient-stock, so the extra branches are unreachable where they didn't exist before.
 */
import { useTranslation } from 'react-i18next'
import { checkoutErrorKey, parseInsufficientStock } from './errorKeys'

export function CheckoutErrorText({ error }: { error: unknown }) {
  const { t } = useTranslation()
  const stockErr = parseInsufficientStock(error)
  let message: string
  if (stockErr) {
    message = t('pos.payment.insufficientStock', {
      itemName: stockErr.itemName,
      available: stockErr.available,
    })
  } else {
    const key = checkoutErrorKey(error)
    message = key ? t(key) : (error as Error).message
  }
  return (
    <p className="mb-3 text-xs text-loss" role="alert">
      {message}
    </p>
  )
}
