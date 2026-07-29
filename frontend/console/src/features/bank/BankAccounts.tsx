import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Plus, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { useBankAccounts, useCreateBankAccount } from './api'
import { DialogOverlay } from './parts'

/**
 * Bank accounts — the roster of company bank accounts: a Card table (name/account number/
 * currency/status) plus a "New bank account" dialog. Each row opens its reconciliation
 * workspace (/bank/:id). Owner/manager only (gateway-enforced). All copy via i18n (rule 9).
 */
export function BankAccounts() {
  const { t } = useTranslation()
  const { company } = useSession()
  const [dialogOpen, setDialogOpen] = useState(false)

  const query = useBankAccounts({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('bank.accounts.noCompany')} hint={t('bank.accounts.noCompanyHint')} />
  }

  const accounts = query.data ?? []

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('bank.accounts.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('bank.accounts.subtitle')}</p>
        </div>
        <Button type="button" onClick={() => setDialogOpen(true)}>
          <Plus className="size-4" />
          {t('bank.accounts.add')}
        </Button>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('bank.accounts.error')}
        </Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : accounts.length === 0 ? (
        <EmptyState title={t('bank.accounts.empty')} hint={t('bank.accounts.emptyHint')} />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('bank.accounts.colName')}</th>
                <th className="px-4 py-3">{t('bank.accounts.colAccountNumber')}</th>
                <th className="px-4 py-3">{t('bank.accounts.colCurrency')}</th>
                <th className="px-4 py-3">{t('bank.accounts.colStatus')}</th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => (
                <tr key={a.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3">
                    <Link
                      to={`/bank/${a.id}`}
                      className="font-semibold text-ink hover:text-brand-700 hover:underline"
                    >
                      {a.name}
                    </Link>
                  </td>
                  <td className="px-4 py-3 font-mono text-ink-2">
                    {a.accountNumber ?? t('bank.accounts.noValue')}
                  </td>
                  <td className="px-4 py-3 font-mono text-ink-2">{a.currency}</td>
                  <td className="px-4 py-3">
                    <Badge tone={a.active ? 'profit' : 'neutral'}>
                      {a.active ? t('bank.accounts.statusActive') : t('bank.accounts.statusInactive')}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {dialogOpen ? (
        <NewBankAccountDialog
          companyId={company.companyId}
          actor={company.actor}
          defaultCurrency={company.baseCurrency}
          onClose={() => setDialogOpen(false)}
        />
      ) : null}
    </div>
  )
}

function NewBankAccountDialog({
  companyId,
  actor,
  defaultCurrency,
  onClose,
}: {
  companyId: string
  actor: string
  defaultCurrency: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [accountNumber, setAccountNumber] = useState('')
  const [currency, setCurrency] = useState(defaultCurrency)
  const mutation = useCreateBankAccount({ companyId, actor })

  const trimmedCurrency = currency.trim().toUpperCase()
  const canSubmit = !!name.trim() && /^[A-Z]{3}$/.test(trimmedCurrency) && !mutation.isPending

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!canSubmit) return
    mutation.mutate(
      {
        name: name.trim(),
        accountNumber: accountNumber.trim() || undefined,
        currency: trimmedCurrency,
      },
      { onSuccess: () => onClose() },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('bank.accounts.createDialog.title')}
        </h2>

        <Field label={t('bank.accounts.createDialog.nameLabel')} htmlFor="bank-acct-name">
          <TextInput
            id="bank-acct-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            autoFocus
          />
        </Field>

        <Field
          label={t('bank.accounts.createDialog.accountNumberLabel')}
          htmlFor="bank-acct-number"
        >
          <TextInput
            id="bank-acct-number"
            value={accountNumber}
            onChange={(e) => setAccountNumber(e.target.value)}
          />
        </Field>

        <Field
          label={t('bank.accounts.createDialog.currencyLabel')}
          htmlFor="bank-acct-currency"
          hint={t('bank.accounts.createDialog.currencyHint')}
        >
          <TextInput
            id="bank-acct-currency"
            value={currency}
            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            maxLength={3}
            required
          />
        </Field>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('bank.accounts.createDialog.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending
              ? t('bank.accounts.createDialog.submitting')
              : t('bank.accounts.createDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
