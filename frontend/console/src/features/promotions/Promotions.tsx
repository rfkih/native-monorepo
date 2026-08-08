/**
 * Promotions — the Phase-3 promotions admin page (ADR 0026): a vertical switch (restaurant / carwash
 * / barbershop, each hitting its own API base — carwash/barbershop are cloned from the SAME contract
 * as restaurant) over two sections, Rules and Coupons. Owner/manager only — routed/gated exactly like
 * /budgets (App.tsx `canDashboard`), a company-wide back-office surface, not outlet-scoped.
 *
 * The line-scope ITEM/CATEGORY picker reuses the SAME catalog hooks the POS terminals use (restaurant
 * menu items/categories via features/pos's useMenu/useCategories; carwash/barbershop packages/services
 * via servicepos's useCatalogPackages) so a promo rule always scopes to something that actually exists
 * on the menu/catalog.
 *
 * Money rule (rule 8): formatMoney for every amount. Strings rule (rule 9): every label is an i18n
 * key, en+id.
 */
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, Tag, Ticket, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Segmented } from '@/components/ui/Segmented'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatPercent } from '@/lib/money'
import { useMenu, useCategories } from '@/features/pos/api'
import { useCatalogPackages } from '@/features/servicepos/api'
import { carwashConfig } from '@/features/servicepos/carwashConfig'
import { barbershopConfig } from '@/features/servicepos/barbershopConfig'
import {
  usePromoRules,
  useCoupons,
  type CouponResponse,
  type PromoRuleResponse,
  type PromotionVertical,
} from './api'
import { dowMaskToDays, timeOfDayLabel, weekdayShortLabel } from './format'
import { RuleDialog, type ScopeOption } from './RuleDialog'
import { CouponDialog } from './CouponDialog'

export function Promotions() {
  const { t } = useTranslation()
  const { company } = useSession()
  if (!company) {
    return <EmptyState title={t('promotions.noCompany')} hint={t('promotions.noCompanyHint')} />
  }
  return <PromotionsInner company={company} />
}

type RuleDialogState = { mode: 'create' } | { mode: 'edit'; rule: PromoRuleResponse } | null
type CouponDialogState = { mode: 'create' } | { mode: 'edit'; coupon: CouponResponse } | null

function PromotionsInner({ company }: { company: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const currency = company.baseCurrency

  const [vertical, setVertical] = useState<PromotionVertical>('restaurant')

  const rulesQuery = usePromoRules(vertical, company)
  const couponsQuery = useCoupons(vertical, company)

  // Scope-ref catalog sources — all three fetched unconditionally (cheap, cached, avoids a refetch
  // on every vertical-tab switch) and picked from below by the active vertical.
  const menuItemsQuery = useMenu(company)
  const categoriesQuery = useCategories(company)
  const carwashPackagesQuery = useCatalogPackages(carwashConfig, company)
  const barbershopPackagesQuery = useCatalogPackages(barbershopConfig, company)

  const itemOptions: ScopeOption[] = useMemo(() => {
    if (vertical === 'restaurant') {
      return (menuItemsQuery.data ?? []).filter((i) => i.active).map((i) => ({ id: i.id, name: i.name }))
    }
    const source = vertical === 'carwash' ? carwashPackagesQuery.data : barbershopPackagesQuery.data
    return (source ?? []).filter((p) => p.active).map((p) => ({ id: p.id, name: p.name }))
  }, [vertical, menuItemsQuery.data, carwashPackagesQuery.data, barbershopPackagesQuery.data])

  // CATEGORY scope is a restaurant-only dimension (PromoScopeKind.CATEGORY javadoc).
  const categoryOptions: ScopeOption[] = useMemo(() => {
    if (vertical !== 'restaurant') return []
    return (categoriesQuery.data ?? []).filter((c) => c.active).map((c) => ({ id: c.id, name: c.name }))
  }, [vertical, categoriesQuery.data])

  const itemNameById = useMemo(() => new Map(itemOptions.map((o) => [o.id, o.name])), [itemOptions])
  const categoryNameById = useMemo(() => new Map(categoryOptions.map((o) => [o.id, o.name])), [categoryOptions])

  const rules = useMemo(() => rulesQuery.data ?? [], [rulesQuery.data])
  const coupons = couponsQuery.data ?? []
  const ruleNameById = useMemo(() => new Map(rules.map((r) => [r.id, r.name])), [rules])

  const [ruleDialog, setRuleDialog] = useState<RuleDialogState>(null)
  const [couponDialog, setCouponDialog] = useState<CouponDialogState>(null)

  const verticalOptions: { value: PromotionVertical; label: string }[] = [
    { value: 'restaurant', label: t('promotions.vertical.restaurant') },
    { value: 'carwash', label: t('promotions.vertical.carwash') },
    { value: 'barbershop', label: t('promotions.vertical.barbershop') },
  ]

  function ruleValueSummary(rule: PromoRuleResponse): string {
    if (rule.ruleType === 'AMOUNT_OFF_ORDER') {
      return t('promotions.summaryAmountOrder', {
        amount: formatMoney(rule.amountMinor ?? 0, rule.currency ?? currency, locale),
      })
    }
    const pct = formatPercent((rule.rateBp ?? 0) / 10000, locale)
    if (rule.ruleType === 'PERCENT_OFF_ORDER') {
      return t('promotions.summaryPercentOrder', { pct })
    }
    const map = rule.scopeKind === 'CATEGORY' ? categoryNameById : itemNameById
    const refName = rule.scopeRefId ? (map.get(rule.scopeRefId) ?? t('promotions.unknownScopeRef')) : t('promotions.unknownScopeRef')
    return t('promotions.summaryPercentLine', { pct, name: refName })
  }

  function happyHourSummary(rule: PromoRuleResponse): string | null {
    if (!rule.windowStart || !rule.windowEnd) return null
    const dayLabel =
      rule.dowMask != null
        ? [...dowMaskToDays(rule.dowMask)].sort().map((d) => weekdayShortLabel(d, locale)).join(', ')
        : t('promotions.everyDay')
    return `${dayLabel} · ${timeOfDayLabel(rule.windowStart, locale)}–${timeOfDayLabel(rule.windowEnd, locale)}`
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">{t('promotions.title')}</h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('promotions.subtitle')}</p>
        </div>
        <Segmented options={verticalOptions} value={vertical} onChange={setVertical} ariaLabel={t('promotions.vertical.label')} />
      </div>

      {/* ---- Rules ---- */}
      <section className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-3">
          <h2 className="flex items-center gap-2 font-display text-lg font-semibold text-ink">
            <Tag className="size-[18px] text-emerald-2" aria-hidden />
            {t('promotions.rulesTitle')}
          </h2>
          <Button type="button" size="sm" onClick={() => setRuleDialog({ mode: 'create' })}>
            <Plus className="size-[14px]" aria-hidden />
            {t('promotions.newRule')}
          </Button>
        </div>

        {rulesQuery.isError ? (
          <Card className="p-8 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" />
            {t('promotions.errors.loadRules')}
          </Card>
        ) : rulesQuery.isLoading ? (
          <ListSkeleton rows={3} />
        ) : rules.length === 0 ? (
          <EmptyState title={t('promotions.emptyRules')} hint={t('promotions.emptyRulesHint')} />
        ) : (
          <div className="flex flex-col gap-2.5">
            {rules.map((rule) => {
              const happyHour = happyHourSummary(rule)
              return (
                <Card key={rule.id} className="p-4">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold text-ink">{rule.name}</span>
                        <Badge tone={rule.active ? 'profit' : 'neutral'}>
                          {rule.active ? t('promotions.statusActive') : t('promotions.statusInactive')}
                        </Badge>
                        {rule.exclusive ? <Badge tone="info">{t('promotions.badgeExclusive')}</Badge> : null}
                        {rule.requiresCoupon ? <Badge tone="amber">{t('promotions.badgeRequiresCoupon')}</Badge> : null}
                      </div>
                      <p className="mt-1 text-sm text-ink-2">{ruleValueSummary(rule)}</p>
                      {happyHour ? <p className="mt-0.5 text-xs text-ink-3">{t('promotions.happyHourLabel')}: {happyHour}</p> : null}
                      {rule.minSubtotalMinor ? (
                        <p className="mt-0.5 text-xs text-ink-3">
                          {t('promotions.minSubtotalLabel')}: {formatMoney(rule.minSubtotalMinor, rule.currency ?? currency, locale)}
                        </p>
                      ) : null}
                    </div>
                    <Button type="button" variant="outline" size="sm" onClick={() => setRuleDialog({ mode: 'edit', rule })}>
                      {t('promotions.edit')}
                    </Button>
                  </div>
                </Card>
              )
            })}
          </div>
        )}
      </section>

      {/* ---- Coupons ---- */}
      <section className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-3">
          <h2 className="flex items-center gap-2 font-display text-lg font-semibold text-ink">
            <Ticket className="size-[18px] text-emerald-2" aria-hidden />
            {t('promotions.couponsTitle')}
          </h2>
          <Button
            type="button"
            size="sm"
            variant="secondary"
            onClick={() => setCouponDialog({ mode: 'create' })}
            disabled={rules.length === 0}
          >
            <Plus className="size-[14px]" aria-hidden />
            {t('promotions.newCoupon')}
          </Button>
        </div>

        {rules.length === 0 ? (
          <p className="text-sm text-ink-3">{t('promotions.couponsNeedRule')}</p>
        ) : null}

        {couponsQuery.isError ? (
          <Card className="p-8 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" />
            {t('promotions.errors.loadCoupons')}
          </Card>
        ) : couponsQuery.isLoading ? (
          <ListSkeleton rows={3} />
        ) : coupons.length === 0 ? (
          <EmptyState title={t('promotions.emptyCoupons')} hint={t('promotions.emptyCouponsHint')} />
        ) : (
          <Card className="overflow-x-auto rounded-[20px]">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                  <th className="px-4 py-3">{t('promotions.colCode')}</th>
                  <th className="px-4 py-3">{t('promotions.colLinkedRule')}</th>
                  <th className="px-4 py-3 text-right">{t('promotions.colRedemptions')}</th>
                  <th className="px-4 py-3">{t('promotions.colExpires')}</th>
                  <th className="px-4 py-3">{t('promotions.colStatus')}</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody>
                {coupons.map((c) => (
                  <tr key={c.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                    <td className="px-4 py-3 font-mono font-semibold text-ink">{c.code}</td>
                    <td className="px-4 py-3 text-ink-2">{ruleNameById.get(c.ruleId) ?? t('promotions.unknownRule')}</td>
                    <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                      {c.redeemedCount} / {c.maxRedemptions}
                    </td>
                    <td className="px-4 py-3 text-ink-2">
                      {c.expiresAt
                        ? new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(c.expiresAt))
                        : t('promotions.neverExpires')}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={c.active ? 'profit' : 'neutral'}>
                        {c.active ? t('promotions.statusActive') : t('promotions.statusInactive')}
                      </Badge>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Button type="button" variant="outline" size="sm" onClick={() => setCouponDialog({ mode: 'edit', coupon: c })}>
                        {t('promotions.edit')}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        )}
      </section>

      {ruleDialog ? (
        <RuleDialog
          session={company}
          vertical={vertical}
          rule={ruleDialog.mode === 'edit' ? ruleDialog.rule : null}
          itemOptions={itemOptions}
          categoryOptions={categoryOptions}
          currency={currency}
          locale={locale}
          onClose={() => setRuleDialog(null)}
        />
      ) : null}

      {couponDialog ? (
        <CouponDialog
          session={company}
          vertical={vertical}
          coupon={couponDialog.mode === 'edit' ? couponDialog.coupon : null}
          rules={rules}
          onClose={() => setCouponDialog(null)}
        />
      ) : null}
    </div>
  )
}
