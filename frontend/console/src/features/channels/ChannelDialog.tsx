/**
 * ChannelDialog — create/edit a sales channel (ADR 0036 Phase B3). Code is normalized to
 * uppercase (letters/digits/`-`/`_` only) as it's typed, mirroring the server's own canonical
 * form so the preview always matches what will be saved; code is fixed forever once created (no
 * PATCH field for it), so it is a read-only chip in edit mode — mirrors
 * features/promotions/CouponDialog.tsx's immutable-code-in-edit-mode pattern.
 *
 * Any failure (incl. the 409 duplicate-code conflict) surfaces the API's own error message
 * directly — no separate mapped i18n key for it (contract: "409 duplicate-code surfaces the API
 * error message").
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import type { CompanySession } from '@/lib/session'
import { normalizeChannelCode } from './channelCode'
import { useCreateSalesChannel, useUpdateSalesChannel, type SalesChannel } from './channelsApi'
import { DialogOverlay } from './parts'

interface Props {
  session: CompanySession
  /** null = create mode. */
  channel: SalesChannel | null
  onClose: () => void
}

export function ChannelDialog({ session, channel, onClose }: Props) {
  const { t } = useTranslation()
  const isEdit = channel != null
  const createChannel = useCreateSalesChannel(session)
  const updateChannel = useUpdateSalesChannel(session)

  const [code, setCode] = useState(channel?.code ?? '')
  const [name, setName] = useState(channel?.name ?? '')
  const [error, setError] = useState<string | null>(null)

  const isPending = createChannel.isPending || updateChannel.isPending

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    if (!isEdit && !code.trim()) {
      setError(t('channels.dialog.codeRequired'))
      return
    }
    if (!name.trim()) {
      setError(t('channels.dialog.nameRequired'))
      return
    }
    try {
      if (isEdit && channel) {
        await updateChannel.mutateAsync({ id: channel.id, name: name.trim() })
      } else {
        await createChannel.mutateAsync({ code: normalizeChannelCode(code), name: name.trim() })
      }
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="font-display text-lg font-semibold text-ink">
            {isEdit ? t('channels.dialog.editTitle') : t('channels.dialog.createTitle')}
          </h2>
          <button
            type="button"
            aria-label={t('common.close')}
            onClick={onClose}
            className="text-ink-3 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-5" />
          </button>
        </div>

        <Field
          label={t('channels.dialog.codeLabel')}
          htmlFor="channel-code"
          hint={isEdit ? undefined : t('channels.dialog.codeHint')}
        >
          <TextInput
            id="channel-code"
            value={code}
            disabled={isEdit}
            placeholder={t('channels.dialog.codePlaceholder')}
            onChange={(e) => setCode(normalizeChannelCode(e.target.value))}
            className="font-mono uppercase tracking-wider"
            maxLength={40}
            autoFocus={!isEdit}
          />
        </Field>

        <Field label={t('channels.dialog.nameLabel')} htmlFor="channel-name">
          <TextInput
            id="channel-name"
            value={name}
            placeholder={t('channels.dialog.namePlaceholder')}
            onChange={(e) => setName(e.target.value)}
            maxLength={100}
            autoFocus={isEdit}
          />
        </Field>

        {error ? (
          <p className="text-sm text-loss" role="alert">
            {error}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={isPending}>
            {isPending ? (
              <Spinner />
            ) : isEdit ? (
              t('channels.dialog.submitSave')
            ) : (
              t('channels.dialog.submitCreate')
            )}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
