import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Plus, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { EmptyState } from '@/features/_shared/financeUi'
import { LedgerPhoneList } from '@/features/_shared/LedgerPhoneList'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { useBills, type BillStatus } from './api'
import { formatDate } from './format'
import { BillStatusBadge } from './parts'

type StatusFilter = BillStatus | 'ALL'

/**
 * Bills — the AP bill list: a status filter, rows with number/vendor/status/total/
 * outstanding, click-through to the detail page, and a "New bill" button. Owner/manager only.
 */
export function BillsList() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const isPhone = useIsPhone()
  const locale = localeOf(i18n.language)
  const [status, setStatus] = useState<StatusFilter>('ALL')

  const query = useBills({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    status: status === 'ALL' ? undefined : status,
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('ap.bills.noCompany')} hint={t('ap.bills.noCompanyHint')} />
  }

  const bills = query.data ?? []
  const statuses: BillStatus[] = ['DRAFT', 'POSTED', 'PARTIALLY_PAID', 'PAID', 'VOID']
  const options: { value: StatusFilter; label: string }[] = [
    { value: 'ALL', label: t('ap.bills.filterAll') },
    ...statuses.map((s) => ({
      value: s as StatusFilter,
      label: t(`ap.bills.status.${s}` as Parameters<typeof t>[0]),
    })),
  ]

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('ap.bills.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('ap.bills.subtitle')}</p>
        </div>
        <Link to="/bills/new">
          <Button type="button">
            <Plus className="size-4" />
            {t('ap.bills.newBill')}
          </Button>
        </Link>
      </div>

      <Segmented<StatusFilter>
        options={options}
        value={status}
        onChange={setStatus}
        ariaLabel={t('ap.bills.filterAll')}
        className="flex-wrap"
      />

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('ap.bills.error')}
        </Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : bills.length === 0 ? (
        <EmptyState title={t('ap.bills.empty')} hint={t('ap.bills.emptyHint')} />
      ) : isPhone ? (
        /* Phone (Native Console Android): two-line rows instead of the 7-column table. */
        <LedgerPhoneList
          locale={locale}
          rows={bills.map((bill) => ({
            id: bill.id,
            to: `/bills/${bill.id}`,
            party: bill.vendorName,
            meta: `${bill.billNumber} · ${formatDate(bill.billDate, locale)}`,
            badge: <BillStatusBadge status={bill.status} />,
            due: formatDate(bill.dueDate, locale),
            outstandingMinor: bill.outstandingMinor,
            currency: bill.currency,
          }))}
        />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('ap.bills.colNumber')}</th>
                <th className="px-4 py-3">{t('ap.bills.colVendor')}</th>
                <th className="px-4 py-3">{t('ap.bills.colStatus')}</th>
                <th className="px-4 py-3">{t('ap.bills.colBillDate')}</th>
                <th className="px-4 py-3">{t('ap.bills.colDueDate')}</th>
                <th className="px-4 py-3 text-right">{t('ap.bills.colTotal')}</th>
                <th className="px-4 py-3 text-right">{t('ap.bills.colOutstanding')}</th>
              </tr>
            </thead>
            <tbody>
              {bills.map((bill) => (
                <tr key={bill.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3">
                    <Link
                      to={`/bills/${bill.id}`}
                      className="font-mono font-semibold text-ink hover:text-brand-700 hover:underline"
                    >
                      {bill.billNumber}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-ink-2">{bill.vendorName}</td>
                  <td className="px-4 py-3">
                    <BillStatusBadge status={bill.status} />
                  </td>
                  <td className="px-4 py-3 text-ink-3">{formatDate(bill.billDate, locale)}</td>
                  <td className="px-4 py-3 text-ink-3">{formatDate(bill.dueDate, locale)}</td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink">
                    {formatMoney(bill.totalMinor, bill.currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(bill.outstandingMinor, bill.currency, locale)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  )
}
