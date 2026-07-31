/**
 * ErrorDiagnostics — the AI-native replacement for a bare "could not load" line.
 *
 * Renders the human message PLUS: (1) a RECOVERY action derived from the actual failure class —
 * an expired/absent token offers a re-login, a stale company claim (403
 * invalid-company-selection) offers a silent session refresh; (2) an expandable technical detail
 * (status, problem type, server traceId, token state); (3) a one-click "copy diagnostics" that
 * produces the structured JSON bundle (lib/diagnostics.ts) an assistant can act on without
 * reproduction.
 *
 * Strings rule (rule 9): all copy through i18n. Privacy: the bundle carries derived token state
 * only — never the token, never request bodies (see lib/diagnostics.ts).
 */
import { useSyncExternalStore, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ClipboardCopy, RefreshCw, LogIn, ChevronDown, ChevronUp } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { useAuth } from '@/lib/authContext'
import {
  diagnosticBundle,
  lastFailure,
  subscribeFailures,
  type FailureRecord,
} from '@/lib/diagnostics'

interface Props {
  /** The headline the feature already shows (e.g. t('dashboard.error')). */
  message: string
  /** Restrict the surfaced failure to this API path prefix (else the most recent failure). */
  pathPrefix?: string
  /** Called after a successful session refresh/re-login recovery so the caller can refetch. */
  onRecovered?: () => void
}

type RecoveryKind = 'relogin' | 'refreshSession' | null

function recoveryFor(failure: FailureRecord | null): RecoveryKind {
  if (!failure) return null
  if (failure.status === 401) {
    // Absent or expired token — only a (re-)login mints a new one.
    const ts = failure.tokenState
    if (!ts || !ts.present || (ts.expiresInSeconds != null && ts.expiresInSeconds <= 0)) {
      return 'relogin'
    }
    // A present, unexpired token that still 401s is unusual — re-login is still the best lever.
    return 'relogin'
  }
  if (failure.status === 403) {
    // The claim set is stale (a company was added/bound after this token was minted). The
    // gateway's selection rejection is body-less, so ALSO recognize the case from the derived
    // token state: the selected company provably outside the claim → a refresh mints a token
    // with the enlarged set.
    if (
      failure.problemType?.includes('invalid-company-selection') ||
      failure.tokenState?.selectedCompanyInClaim === false
    ) {
      return 'refreshSession'
    }
  }
  return null
}

/** True when the failure is the stale-company-claim 403 (see {@link recoveryFor}). */
function isStaleCompanyClaim(failure: FailureRecord | null): boolean {
  return (
    failure?.status === 403 &&
    (failure.problemType?.includes('invalid-company-selection') === true ||
      failure.tokenState?.selectedCompanyInClaim === false)
  )
}

export function ErrorDiagnostics({ message, pathPrefix, onRecovered }: Props) {
  const { t } = useTranslation()
  const auth = useAuth()
  const [open, setOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  const [refreshing, setRefreshing] = useState(false)

  const failure = useSyncExternalStore(
    subscribeFailures,
    () => lastFailure(pathPrefix),
    () => null,
  )
  const recovery = recoveryFor(failure)

  async function copyBundle() {
    try {
      await navigator.clipboard.writeText(diagnosticBundle(failure))
      setCopied(true)
      setTimeout(() => setCopied(false), 2500)
    } catch {
      // Clipboard unavailable (permissions) — the detail block below stays readable/selectable.
      setOpen(true)
    }
  }

  async function refreshSession() {
    setRefreshing(true)
    try {
      const ok = await auth.refresh()
      if (ok) onRecovered?.()
    } finally {
      setRefreshing(false)
    }
  }

  return (
    <Card className="p-8 text-center">
      <p className="text-sm text-loss">{message}</p>

      {failure ? (
        <p className="mt-2 text-xs text-ink-3">
          {t('errorDiag.summary', {
            status: failure.status,
            path: failure.path,
          })}
          {failure.status === 401 &&
          failure.tokenState?.expiresInSeconds != null &&
          failure.tokenState.expiresInSeconds <= 0
            ? ` — ${t('errorDiag.tokenExpired')}`
            : failure.status === 401 && failure.tokenState && !failure.tokenState.present
              ? ` — ${t('errorDiag.tokenMissing')}`
              : isStaleCompanyClaim(failure)
                ? ` — ${t('errorDiag.staleCompanyClaim')}`
                : ''}
        </p>
      ) : null}

      <div className="mt-4 flex flex-wrap items-center justify-center gap-2">
        {recovery === 'relogin' ? (
          <Button size="sm" onClick={() => auth.login()}>
            <LogIn className="size-4" aria-hidden="true" />
            {t('errorDiag.relogin')}
          </Button>
        ) : null}
        {recovery === 'refreshSession' ? (
          <Button size="sm" onClick={refreshSession} disabled={refreshing}>
            <RefreshCw className="size-4" aria-hidden="true" />
            {refreshing ? t('errorDiag.refreshing') : t('errorDiag.refreshSession')}
          </Button>
        ) : null}
        <Button variant="ghost" size="sm" onClick={copyBundle}>
          <ClipboardCopy className="size-4" aria-hidden="true" />
          {copied ? t('errorDiag.copied') : t('errorDiag.copy')}
        </Button>
        {failure ? (
          <Button variant="ghost" size="sm" onClick={() => setOpen((v) => !v)}>
            {open ? (
              <ChevronUp className="size-4" aria-hidden="true" />
            ) : (
              <ChevronDown className="size-4" aria-hidden="true" />
            )}
            {t('errorDiag.detail')}
          </Button>
        ) : null}
      </div>

      {open && failure ? (
        <pre className="mt-4 max-h-64 overflow-auto rounded-xl border border-line bg-paper p-4 text-left font-mono text-[11px] leading-relaxed text-ink-2">
          {JSON.stringify(failure, null, 2)}
        </pre>
      ) : null}
    </Card>
  )
}
