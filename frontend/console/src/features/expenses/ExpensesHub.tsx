/**
 * `/expenses` — the manager expenses hub (ADR 0030 Phase E7): a thin composition of the tab
 * bodies (`ExpenseInbox` for the SUBMITTED queue, `ExpensesList` for the tenant-wide claim
 * history + settlement actions, `CompanyExpensesList` for the ADR 0072 P3 company-expense feed)
 * behind one route, plus a link to the separate `/expenses/categories` admin page (mirrors how
 * `/invoices/new` has no nav entry of its own — reachable only via a button on its parent page).
 *
 * The hub itself stays HR-gated (owner/manager/hr — see App.tsx's `expensesAllowed`); the
 * "Catat pengeluaran" button and the "Perusahaan" tab are FURTHER gated to the FINANCE-capable
 * session (owner/accountant — `/api/v1/company-expenses/**` is FINANCE_ROLES at the gateway), so
 * they simply don't render for an `hr`-only login rather than linking to a route that would 403.
 * Reads the SAME `canFinance` predicate App.tsx's `financeOk` uses, over the SAME merged role set
 * (ADR 0049 P3b `effectiveRoles`) — this file has no route-level gate of its own to read from.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Plus, Settings } from 'lucide-react'
import { Segmented } from '@/components/ui/Segmented'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { canFinance } from '@/lib/rolePreset'
import { ExpenseInbox } from './ExpenseInbox'
import { ExpensesList } from './ExpensesList'
import { CompanyExpensesList } from './CompanyExpensesList'

type Tab = 'inbox' | 'all' | 'company'

export function ExpensesHub() {
  const { t } = useTranslation()
  const { roles, elevatedRoles } = useAuth()
  const financeOk = canFinance(effectiveRoles(roles, elevatedRoles))
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
        <div className="flex flex-wrap items-center gap-2.5">
          {financeOk ? (
            <Link
              to="/expenses/record"
              className="inline-flex items-center gap-1.5 rounded-xl bg-emerald px-3.5 py-2 text-sm font-bold text-on-emerald shadow-sm transition-colors hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            >
              <Plus className="size-4" aria-hidden="true" />
              {t('expenses.hub.recordExpense')}
            </Link>
          ) : null}
          <Link
            to="/expenses/categories"
            className="inline-flex items-center gap-1.5 rounded-xl border border-line px-3.5 py-2 text-sm font-semibold text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-emerald"
          >
            <Settings className="size-4" aria-hidden="true" />
            {t('expenses.hub.manageCategories')}
          </Link>
        </div>
      </div>

      <Segmented<Tab>
        options={[
          { value: 'inbox', label: t('expenses.hub.tabInbox') },
          { value: 'all', label: t('expenses.hub.tabAll') },
          ...(financeOk ? [{ value: 'company' as const, label: t('expenses.hub.tabCompany') }] : []),
        ]}
        value={tab}
        onChange={setTab}
        ariaLabel={t('expenses.hub.tabsLabel')}
      />

      {tab === 'inbox' ? (
        <ExpenseInbox />
      ) : tab === 'all' ? (
        <ExpensesList />
      ) : financeOk ? (
        <CompanyExpensesList />
      ) : (
        <ExpenseInbox />
      )}
    </div>
  )
}
