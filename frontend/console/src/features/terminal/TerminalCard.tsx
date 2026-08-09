/**
 * TerminalCard — the outlet detail screen's "Terminal" tab (two-app + outlet-terminal program,
 * ADR 0049 Phase 2): manage the Business-app till's device (kiosk) login and the outlet's
 * require-PIN operator policy. Owner/manager only — the caller (`OrgUnitDetail`) gates the whole
 * tab; this component assumes it is already reachable only by those roles.
 *
 * Credential hygiene (rule 6): the device password is never fetched until the owner/manager
 * explicitly clicks "Show login", is held only in this component's local state, and "Hide" drops it
 * from state immediately. Nothing here ever calls `console.log` on a password/PIN, and the revealed
 * value never touches `localStorage` or the TanStack Query *query* cache (see
 * `features/terminal/api.ts` for why the reveal call is a mutation, not a query). The reveal/create/
 * reset MUTATIONS do retain their `data` in the MutationCache for the default gcTime, so we
 * explicitly `.reset()` all three on Hide, on Remove, and on unmount — otherwise the plaintext would
 * survive a "Hide" or a navigation-away for ~5 min (code review W1).
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, Eye, EyeOff, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field } from '@/components/ui/Field'
import { Skeleton } from '@/components/ui/Skeleton'
import { ToggleRow } from '@/components/ui/ToggleRow'
import { DialogOverlay } from '@/features/org/parts'
import {
  isDeviceCredentialAlreadyExists,
  isDeviceCredentialNotFound,
  usePinPolicy,
  useCreateDeviceCredential,
  useDeleteDeviceCredential,
  useResetDeviceCredential,
  useRevealDeviceCredential,
  useSetPinPolicy,
  type DeviceCredential,
} from './api'

export function TerminalCard({
  outletId,
  companyId,
  actor,
}: {
  outletId: string
  companyId: string
  actor: string
}) {
  return (
    <div className="flex flex-col gap-4">
      <DeviceLoginCard outletId={outletId} companyId={companyId} actor={actor} />
      <RequirePinCard outletId={outletId} companyId={companyId} actor={actor} />
    </div>
  )
}

// ---------------------------------------------------------------------------
// Device (terminal) credential
// ---------------------------------------------------------------------------

/** The card's local view of the credential — never anything but ephemeral component state. */
type CredentialState =
  | { kind: 'unknown' }
  | { kind: 'notFound' }
  | { kind: 'revealed'; data: DeviceCredential; justIssued: boolean }

function DeviceLoginCard({
  outletId,
  companyId,
  actor,
}: {
  outletId: string
  companyId: string
  actor: string
}) {
  const { t } = useTranslation()
  const [state, setState] = useState<CredentialState>({ kind: 'unknown' })
  const [showPassword, setShowPassword] = useState(false)
  const [confirmingRemove, setConfirmingRemove] = useState(false)

  const reveal = useRevealDeviceCredential({ companyId, actor, outletId })
  const create = useCreateDeviceCredential({ companyId, actor, outletId })
  const reset = useResetDeviceCredential({ companyId, actor, outletId })
  const del = useDeleteDeviceCredential({ companyId, actor, outletId })

  // Drop the decrypted password from the MutationCache when the card unmounts (e.g. navigating
  // away without an explicit Hide) — TanStack retains a mutation's `data` for the default gcTime
  // otherwise (secret hygiene, code review W1). `mutation.reset` is a stable reference, so the
  // empty-deps unmount closure is correct.
  useEffect(() => {
    return () => {
      reveal.reset()
      create.reset()
      reset.reset()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function handleShow() {
    reveal.mutate(undefined, {
      onSuccess: (data) => {
        if (!data) return
        setState({ kind: 'revealed', data, justIssued: false })
        setShowPassword(false)
      },
      onError: (err) => {
        if (isDeviceCredentialNotFound(err)) setState({ kind: 'notFound' })
      },
    })
  }

  function handleCreate() {
    create.mutate(undefined, {
      onSuccess: (data) => {
        if (!data) return
        setState({ kind: 'revealed', data, justIssued: true })
        setShowPassword(true)
      },
    })
  }

  function handleReset() {
    reset.mutate(undefined, {
      onSuccess: (data) => {
        if (!data) return
        setState({ kind: 'revealed', data, justIssued: true })
        setShowPassword(true)
      },
    })
  }

  function handleHide() {
    setState({ kind: 'unknown' })
    setShowPassword(false)
    reveal.reset()
    create.reset()
    reset.reset()
  }

  function handleRemoved() {
    setState({ kind: 'notFound' })
    setShowPassword(false)
    setConfirmingRemove(false)
    reveal.reset()
    create.reset()
    reset.reset()
  }

  return (
    <Card className="p-5">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
            {t('terminal.device.title')}
          </h2>
          <p className="mt-1 text-sm text-ink-3">{t('terminal.device.subtitle')}</p>
        </div>
        {state.kind === 'revealed' ? (
          <button
            type="button"
            onClick={handleHide}
            className="shrink-0 rounded-lg px-2.5 py-1.5 text-xs font-semibold text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-brand-500"
          >
            {t('terminal.device.hide')}
          </button>
        ) : null}
      </div>

      <div className="mt-4">
        {state.kind === 'unknown' ? (
          <div className="space-y-2">
            <Button type="button" variant="outline" onClick={handleShow} disabled={reveal.isPending}>
              {reveal.isPending ? t('terminal.device.checking') : t('terminal.device.show')}
            </Button>
            {reveal.isError && !isDeviceCredentialNotFound(reveal.error) ? (
              <p className="text-sm text-loss">{t('terminal.device.error')}</p>
            ) : null}
          </div>
        ) : state.kind === 'notFound' ? (
          <div className="space-y-3">
            <p className="text-sm text-ink-3">{t('terminal.device.none')}</p>
            <Button type="button" onClick={handleCreate} disabled={create.isPending}>
              {create.isPending ? t('terminal.device.creating') : t('terminal.device.create')}
            </Button>
            {create.isError ? (
              <p className="text-sm text-loss">
                {isDeviceCredentialAlreadyExists(create.error)
                  ? t('terminal.device.alreadyExists')
                  : t('terminal.device.error')}
              </p>
            ) : null}
          </div>
        ) : (
          <div className="space-y-3">
            {state.justIssued ? (
              <p className="rounded-xl border border-amber/30 bg-amber-tint px-3.5 py-2.5 text-xs leading-relaxed text-amber">
                {t('terminal.device.oneTimeNote')}
              </p>
            ) : null}

            <Field label={t('terminal.device.username')}>
              <div className="flex items-center gap-2 rounded-xl bg-paper px-3 py-2">
                <code className="flex-1 truncate font-mono text-sm text-ink">
                  {state.data.username}
                </code>
                <CopyButton text={state.data.username} label={t('terminal.device.copyUsername')} />
              </div>
            </Field>

            <Field label={t('terminal.device.password')}>
              <div className="flex items-center gap-2 rounded-xl bg-paper px-3 py-2">
                <code className="flex-1 truncate font-mono text-sm text-ink">
                  {showPassword ? state.data.password : '••••••••••••'}
                </code>
                <button
                  type="button"
                  aria-label={
                    showPassword ? t('terminal.device.hidePassword') : t('terminal.device.showPassword')
                  }
                  aria-pressed={showPassword}
                  onClick={() => setShowPassword((s) => !s)}
                  className="grid size-8 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-brand-500"
                >
                  {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                </button>
                {showPassword ? (
                  <CopyButton text={state.data.password} label={t('terminal.device.copyPassword')} />
                ) : null}
              </div>
            </Field>

            <div className="flex flex-wrap items-center gap-2 pt-1">
              <Button type="button" variant="outline" onClick={handleReset} disabled={reset.isPending}>
                {reset.isPending ? t('terminal.device.resetting') : t('terminal.device.resetPassword')}
              </Button>
              <button
                type="button"
                onClick={() => setConfirmingRemove(true)}
                className="rounded-lg px-3 py-2 text-xs font-semibold text-loss/80 transition-colors hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-loss"
              >
                {t('terminal.device.remove')}
              </button>
            </div>
            {reset.isError ? <p className="text-sm text-loss">{t('terminal.device.error')}</p> : null}
          </div>
        )}
      </div>

      {confirmingRemove ? (
        <RemoveDeviceCredentialDialog
          deleteMutation={del}
          onRemoved={handleRemoved}
          onClose={() => setConfirmingRemove(false)}
        />
      ) : null}
    </Card>
  )
}

function RemoveDeviceCredentialDialog({
  deleteMutation,
  onRemoved,
  onClose,
}: {
  deleteMutation: ReturnType<typeof useDeleteDeviceCredential>
  onRemoved: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()

  function handleConfirm() {
    deleteMutation.mutate(undefined, { onSuccess: onRemoved })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('terminal.device.removeDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">{t('terminal.device.removeDialog.body')}</p>

        {deleteMutation.isError ? (
          <p className="text-sm text-loss">{t('terminal.device.error')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            className="bg-loss text-white hover:opacity-90"
            onClick={handleConfirm}
            disabled={deleteMutation.isPending}
          >
            {deleteMutation.isPending
              ? t('terminal.device.removeDialog.removing')
              : t('terminal.device.removeDialog.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

/** Copy-to-clipboard icon button — local to this file (mirrors `hr/EmployeeDetailDrawer`'s). */
function CopyButton({ text, label }: { text: string; label: string }) {
  const [copied, setCopied] = useState(false)
  function handleCopy() {
    void navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={handleCopy}
      className="grid size-8 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-brand-500"
    >
      {copied ? <Check className="size-4 text-emerald-2" /> : <Copy className="size-4" />}
    </button>
  )
}

// ---------------------------------------------------------------------------
// Require-PIN policy
// ---------------------------------------------------------------------------

function RequirePinCard({
  outletId,
  companyId,
  actor,
}: {
  outletId: string
  companyId: string
  actor: string
}) {
  const { t } = useTranslation()
  const policyQuery = usePinPolicy({ companyId, actor, outletId, enabled: true })
  const setPolicy = useSetPinPolicy({ companyId, actor })

  // No policy row on file reads as requirePin=true server-side (the safe default) — mirrored here
  // so a still-loading/absent row never flashes the wrong toggle state.
  const requirePin = policyQuery.data?.requirePin ?? true

  return (
    <Card className="p-5">
      <h2 className="text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
        {t('terminal.pin.title')}
      </h2>

      <div className="mt-4">
        {policyQuery.isLoading ? (
          <Skeleton className="h-6 w-full" />
        ) : policyQuery.isError ? (
          <p className="flex items-center gap-2 text-sm text-loss">
            <TriangleAlert className="size-4" />
            {t('terminal.pin.error')}
          </p>
        ) : (
          <ToggleRow
            label={t('terminal.pin.toggleLabel')}
            hint={t('terminal.pin.hint')}
            checked={requirePin}
            onToggle={() => setPolicy.mutate({ outletId, requirePin: !requirePin })}
            disabled={setPolicy.isPending}
          />
        )}
        {setPolicy.isError ? (
          <p className="mt-2 text-sm text-loss">{t('terminal.pin.error')}</p>
        ) : null}
      </div>
    </Card>
  )
}
