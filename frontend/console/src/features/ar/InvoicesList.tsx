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
import { useInvoices, type InvoiceStatus } from './api'
import { formatDate } from './format'
import { InvoiceStatusBadge } from './parts'

type StatusFilter = InvoiceStatus | 'ALL'

/**
 * Invoices — the AR invoice list: a status filter, rows with number/customer/status/total/
 * outstanding, click-through to the detail page, and a "New invoice" button. Owner/manager only.
 */
export function InvoicesList() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const isPhone = useIsPhone()
  const locale = localeOf(i18n.language)
  const [status, setStatus] = useState<StatusFilter>('ALL')

  const query = useInvoices({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    status: status === 'ALL' ? undefined : status,
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('ar.invoices.noCompany')} hint={t('ar.invoices.noCompanyHint')} />
  }

  const invoices = query.data ?? []
  const statuses: InvoiceStatus[] = ['DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOID']
  const options: { value: StatusFilter; label: string }[] = [
    { value: 'ALL', label: t('ar.invoices.filterAll') },
    ...statuses.map((s) => ({
      value: s as StatusFilter,
      label: t(`ar.invoices.status.${s}` as Parameters<typeof t>[0]),
    })),
  ]

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('ar.invoices.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('ar.invoices.subtitle')}</p>
        </div>
        <Link to="/invoices/new">
          <Button type="button">
            <Plus className="size-4" />
            {t('ar.invoices.newInvoice')}
          </Button>
        </Link>
      </div>

      <Segmented<StatusFilter>
        options={options}
        value={status}
        onChange={setStatus}
        ariaLabel={t('ar.invoices.filterAll')}
        className="flex-wrap"
      />

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('ar.invoices.error')}
        </Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : invoices.length === 0 ? (
        <EmptyState title={t('ar.invoices.empty')} hint={t('ar.invoices.emptyHint')} />
      ) : isPhone ? (
        /* Phone (Native Console Android): two-line rows instead of the 7-column table. */
        <LedgerPhoneList
          locale={locale}
          rows={invoices.map((inv) => ({
            id: inv.id,
            to: `/invoices/${inv.id}`,
            party: inv.customerName,
            meta: `${inv.invoiceNumber} · ${formatDate(inv.issueDate, locale)}`,
            badge: <InvoiceStatusBadge status={inv.status} />,
            due: formatDate(inv.dueDate, locale),
            outstandingMinor: inv.outstandingMinor,
            currency: inv.currency,
          }))}
        />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('ar.invoices.colNumber')}</th>
                <th className="px-4 py-3">{t('ar.invoices.colCustomer')}</th>
                <th className="px-4 py-3">{t('ar.invoices.colStatus')}</th>
                <th className="px-4 py-3">{t('ar.invoices.colIssueDate')}</th>
                <th className="px-4 py-3">{t('ar.invoices.colDueDate')}</th>
                <th className="px-4 py-3 text-right">{t('ar.invoices.colTotal')}</th>
                <th className="px-4 py-3 text-right">{t('ar.invoices.colOutstanding')}</th>
              </tr>
            </thead>
            <tbody>
              {invoices.map((inv) => (
                <tr key={inv.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3">
                    <Link
                      to={`/invoices/${inv.id}`}
                      className="font-mono font-semibold text-ink hover:text-brand-700 hover:underline"
                    >
                      {inv.invoiceNumber}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-ink-2">{inv.customerName}</td>
                  <td className="px-4 py-3">
                    <InvoiceStatusBadge status={inv.status} />
                  </td>
                  <td className="px-4 py-3 text-ink-3">{formatDate(inv.issueDate, locale)}</td>
                  <td className="px-4 py-3 text-ink-3">{formatDate(inv.dueDate, locale)}</td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink">
                    {formatMoney(inv.totalMinor, inv.currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(inv.outstandingMinor, inv.currency, locale)}
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
