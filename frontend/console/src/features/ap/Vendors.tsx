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
import { useCreateVendor, useVendors } from './api'
import { DialogOverlay } from './parts'

/**
 * Vendors — the AP vendor roster: a Card table (name/email/tax ID/status) plus a "New
 * vendor" dialog. Owner/manager only (gateway-enforced). All copy via i18n (rule 9).
 */
export function Vendors() {
  const { t } = useTranslation()
  const { company } = useSession()
  const [dialogOpen, setDialogOpen] = useState(false)

  const query = useVendors({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('ap.vendors.noCompany')} hint={t('ap.vendors.noCompanyHint')} />
  }

  const vendors = query.data ?? []

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('ap.vendors.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('ap.vendors.subtitle')}</p>
        </div>
        <Button type="button" onClick={() => setDialogOpen(true)}>
          <Plus className="size-4" />
          {t('ap.vendors.add')}
        </Button>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('ap.vendors.error')}
        </Card>
      ) : query.isLoading ? (
        <ListSkeleton rows={6} />
      ) : vendors.length === 0 ? (
        <EmptyState title={t('ap.vendors.empty')} hint={t('ap.vendors.emptyHint')} />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('ap.vendors.colName')}</th>
                <th className="px-4 py-3">{t('ap.vendors.colEmail')}</th>
                <th className="px-4 py-3">{t('ap.vendors.colTaxId')}</th>
                <th className="px-4 py-3">{t('ap.vendors.colStatus')}</th>
              </tr>
            </thead>
            <tbody>
              {vendors.map((v) => (
                <tr key={v.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3 font-semibold text-ink">{v.name}</td>
                  <td className="px-4 py-3 text-ink-2">{v.email ?? t('ap.vendors.noValue')}</td>
                  <td className="px-4 py-3 font-mono text-ink-2">
                    {v.taxId ?? t('ap.vendors.noValue')}
                  </td>
                  <td className="px-4 py-3">
                    <Badge tone={v.active ? 'profit' : 'neutral'}>
                      {v.active ? t('ap.vendors.statusActive') : t('ap.vendors.statusInactive')}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {dialogOpen ? (
        <NewVendorDialog
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialogOpen(false)}
        />
      ) : null}
    </div>
  )
}

function NewVendorDialog({
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
  const mutation = useCreateVendor({ companyId, actor })

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
          {t('ap.vendors.createDialog.title')}
        </h2>

        <Field label={t('ap.vendors.createDialog.nameLabel')} htmlFor="vend-name">
          <TextInput
            id="vend-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            autoFocus
          />
        </Field>

        <Field label={t('ap.vendors.createDialog.emailLabel')} htmlFor="vend-email">
          <TextInput
            id="vend-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </Field>

        <Field label={t('ap.vendors.createDialog.taxIdLabel')} htmlFor="vend-taxid">
          <TextInput id="vend-taxid" value={taxId} onChange={(e) => setTaxId(e.target.value)} />
        </Field>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('ap.vendors.createDialog.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !name.trim()}>
            {mutation.isPending
              ? t('ap.vendors.createDialog.submitting')
              : t('ap.vendors.createDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
