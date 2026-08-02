/**
 * `/expenses` — the manager expenses hub (ADR 0030 Phase E7): a thin composition of the two tab
 * bodies (`ExpenseInbox` for the SUBMITTED queue, `ExpensesList` for the tenant-wide history +
 * settlement actions) behind one route, plus a link to the separate `/expenses/categories` admin
 * page (mirrors how `/invoices/new` has no nav entry of its own — reachable only via a button on
 * its parent page).
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Settings } from 'lucide-react'
import { Segmented } from '@/components/ui/Segmented'
import { ExpenseInbox } from './ExpenseInbox'
import { ExpensesList } from './ExpensesList'

type Tab = 'inbox' | 'all'

export function ExpensesHub() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('inbox')

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('nav.expenses')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('expenses.hub.subtitle')}</p>
        </div>
        <Link
          to="/expenses/categories"
          className="inline-flex items-center gap-1.5 rounded-xl border border-line px-3.5 py-2 text-sm font-semibold text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-emerald"
        >
          <Settings className="size-4" aria-hidden="true" />
          {t('expenses.hub.manageCategories')}
        </Link>
      </div>

      <Segmented<Tab>
        options={[
          { value: 'inbox', label: t('expenses.hub.tabInbox') },
          { value: 'all', label: t('expenses.hub.tabAll') },
        ]}
        value={tab}
        onChange={setTab}
        ariaLabel={t('expenses.hub.tabsLabel')}
      />

      {tab === 'inbox' ? <ExpenseInbox /> : <ExpensesList />}
    </div>
  )
}
