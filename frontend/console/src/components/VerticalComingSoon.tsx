/**
 * VerticalComingSoon — the branded panel a POS surface shows when the effective outlet belongs
 * to a company whose vertical has no working POS yet (carwash / barbershop). Embeds the
 * OutletPicker so a user assigned to outlets of several verticals can switch to a restaurant
 * outlet without leaving the surface — never trapped.
 */

import { useTranslation } from 'react-i18next'
import { Car, Scissors, Store } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { OutletPicker } from '@/components/OutletPicker'

export function VerticalComingSoon({ vertical }: { vertical: string }) {
  const { t } = useTranslation()
  const Icon = vertical === 'carwash' ? Car : vertical === 'barbershop' ? Scissors : Store
  const verticalLabel = t(`vertical.${vertical}` as Parameters<typeof t>[0], {
    defaultValue: vertical,
  })

  return (
    <div className="grid min-h-[60vh] place-items-center p-6">
      <Card className="w-full max-w-md p-10 text-center">
        <span className="mx-auto grid size-14 place-items-center rounded-2xl bg-emerald-tint">
          <Icon className="size-7 text-emerald-2" aria-hidden="true" />
        </span>
        <div className="mt-4">
          <Badge tone="amber">{t('posVertical.badge')}</Badge>
        </div>
        <h2 className="mt-3 font-display text-xl font-semibold text-ink">
          {t('posVertical.title', { vertical: verticalLabel })}
        </h2>
        <p className="mx-auto mt-2 max-w-sm text-sm leading-relaxed text-ink-3">
          {t('posVertical.body', { vertical: verticalLabel })}
        </p>
        <div className="mt-6 flex flex-col items-center gap-2">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
            {t('posVertical.switchHint')}
          </span>
          <OutletPicker />
        </div>
      </Card>
    </div>
  )
}
