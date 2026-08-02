/**
 * Channels — the sales-channel roster admin page (ADR 0036 Phase B3): a Card table (code/name/
 * status) plus a "New channel" dialog, an edit-name dialog, and a per-row activate/deactivate
 * toggle (deactivating asks for confirmation — it drops the channel out of the POS's ONLINE
 * tender immediately). Owner/manager only — routed/gated exactly like /bank and /promotions
 * (App.tsx `canDashboard`), a company-wide back-office surface, not outlet-scoped.
 *
 * Strings rule (rule 9): every label is an i18n key, en+id. No money on this page (rule 8 N/A).
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession, type CompanySession } from '@/lib/session'
import { cn } from '@/lib/cn'
import { useSalesChannels, useUpdateSalesChannel, type SalesChannel } from './channelsApi'
import { ChannelDialog } from './ChannelDialog'

export function Channels() {
  const { t } = useTranslation()
  const { company } = useSession()
  if (!company) {
    return <EmptyState title={t('channels.noCompany')} hint={t('channels.noCompanyHint')} />
  }
  return <ChannelsInner company={company} />
}

type DialogState = { mode: 'create' } | { mode: 'edit'; channel: SalesChannel } | null

function ChannelsInner({ company }: { company: CompanySession }) {
  const { t } = useTranslation()
  const [dialog, setDialog] = useState<DialogState>(null)

  const query = useSalesChannels(company)
  const channels = query.data ?? []

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('channels.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('channels.subtitle')}</p>
        </div>
        <Button type="button" onClick={() => setDialog({ mode: 'create' })}>
          <Plus className="size-4" />
          {t('channels.add')}
        </Button>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('channels.error')}
        </Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : channels.length === 0 ? (
        <EmptyState title={t('channels.empty')} hint={t('channels.emptyHint')} />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('channels.colCode')}</th>
                <th className="px-4 py-3">{t('channels.colName')}</th>
                <th className="px-4 py-3">{t('channels.colStatus')}</th>
                <th className="px-4 py-3 text-right">{t('channels.colActions')}</th>
              </tr>
            </thead>
            <tbody>
              {channels.map((c) => (
                <ChannelRow key={c.id} session={company} channel={c} onEdit={() => setDialog({ mode: 'edit', channel: c })} />
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {dialog ? (
        <ChannelDialog
          session={company}
          channel={dialog.mode === 'edit' ? dialog.channel : null}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </div>
  )
}

function ChannelRow({
  session,
  channel,
  onEdit,
}: {
  session: CompanySession
  channel: SalesChannel
  onEdit: () => void
}) {
  const { t } = useTranslation()
  const update = useUpdateSalesChannel(session)

  function toggleActive() {
    if (channel.active) {
      if (!window.confirm(t('channels.deactivateConfirm', { name: channel.name }))) return
      update.mutate({ id: channel.id, active: false })
    } else {
      update.mutate({ id: channel.id, active: true })
    }
  }

  return (
    <tr className="border-b border-ink-50 last:border-0 hover:bg-hover">
      <td className="px-4 py-3 font-mono text-ink-2">{channel.code}</td>
      <td className="px-4 py-3 font-semibold text-ink">{channel.name}</td>
      <td className="px-4 py-3">
        <Badge tone={channel.active ? 'profit' : 'neutral'}>
          {channel.active ? t('channels.statusActive') : t('channels.statusInactive')}
        </Badge>
      </td>
      <td className="px-4 py-3">
        <div className="flex justify-end gap-3">
          <button
            type="button"
            onClick={onEdit}
            className="rounded px-2 py-1 text-xs font-semibold text-brand-700 hover:text-brand-600 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            {t('channels.edit')}
          </button>
          {update.isPending ? (
            <Spinner />
          ) : (
            <button
              type="button"
              onClick={toggleActive}
              className={cn(
                'rounded px-2 py-1 text-xs font-semibold focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
                channel.active ? 'text-ink-3 hover:text-loss' : 'text-brand-700 hover:text-brand-600',
              )}
            >
              {channel.active ? t('channels.deactivate') : t('channels.activate')}
            </button>
          )}
        </div>
      </td>
    </tr>
  )
}
