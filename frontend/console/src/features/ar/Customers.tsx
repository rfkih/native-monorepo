import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { useCreateCustomer, useCustomers } from './api'
import { DialogOverlay } from './parts'

/**
 * Customers — the AR customer roster: a Card table (name/email/tax ID/status) plus a "New
 * customer" dialog. Owner/manager only (gateway-enforced). All copy via i18n (rule 9).
 */
export function Customers() {
  const { t } = useTranslation()
  const { company } = useSession()
  const [dialogOpen, setDialogOpen] = useState(false)

  const query = useCustomers({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('ar.customers.noCompany')} hint={t('ar.customers.noCompanyHint')} />
  }

  const customers = query.data ?? []

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('ar.customers.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('ar.customers.subtitle')}</p>
        </div>
        <Button type="button" onClick={() => setDialogOpen(true)}>
          <Plus className="size-4" />
          {t('ar.customers.add')}
        </Button>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('ar.customers.error')}
        </Card>
      ) : query.isLoading ? (
        <ListSkeleton rows={6} />
      ) : customers.length === 0 ? (
        <EmptyState title={t('ar.customers.empty')} hint={t('ar.customers.emptyHint')} />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                  <th className="px-4 py-3">{t('ar.customers.colName')}</th>
                  <th className="px-4 py-3">{t('ar.customers.colEmail')}</th>
                  <th className="px-4 py-3">{t('ar.customers.colTaxId')}</th>
                  <th className="px-4 py-3">{t('ar.customers.colStatus')}</th>
                </tr>
              </thead>
              <tbody>
                {customers.map((c) => (
                  <tr key={c.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                    <td className="px-4 py-3 font-semibold text-ink">{c.name}</td>
                    <td className="px-4 py-3 text-ink-2">{c.email ?? t('ar.customers.noValue')}</td>
                    <td className="px-4 py-3 font-mono text-ink-2">
                      {c.taxId ?? t('ar.customers.noValue')}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={c.active ? 'profit' : 'neutral'}>
                        {c.active ? t('ar.customers.statusActive') : t('ar.customers.statusInactive')}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {dialogOpen ? (
        <NewCustomerDialog
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialogOpen(false)}
        />
      ) : null}
    </div>
  )
}

function NewCustomerDialog({
  companyId,
  actor,
  onClose,
}: {
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [taxId, setTaxId] = useState('')
  const mutation = useCreateCustomer({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate(
      {
        name: name.trim(),
        email: email.trim() || undefined,
        taxId: taxId.trim() || undefined,
      },
      { onSuccess: () => onClose() },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('ar.customers.createDialog.title')}
        </h2>

        <Field label={t('ar.customers.createDialog.nameLabel')} htmlFor="cust-name">
          <TextInput
            id="cust-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            autoFocus
          />
        </Field>

        <Field label={t('ar.customers.createDialog.emailLabel')} htmlFor="cust-email">
          <TextInput
            id="cust-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </Field>

        <Field label={t('ar.customers.createDialog.taxIdLabel')} htmlFor="cust-taxid">
          <TextInput id="cust-taxid" value={taxId} onChange={(e) => setTaxId(e.target.value)} />
        </Field>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('ar.customers.createDialog.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !name.trim()}>
            {mutation.isPending
              ? t('ar.customers.createDialog.submitting')
              : t('ar.customers.createDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
