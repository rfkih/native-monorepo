/**
 * Root-cause block for API failures — renders the technical identity of an {@link ApiError}
 * (method + path + status, problem type/detail, server traceId) with a one-click "Copy details"
 * that puts the full {@link diagnosticBundle} on the clipboard. Paste it into a bug report or an
 * AI assistant and the failure is diagnosable WITHOUT reproduction — "Request failed (502)" alone
 * tells nobody anything.
 *
 * Renders nothing for non-ApiError values (render it unconditionally under any error banner).
 * The technical values are DATA, not copy (rule 9 applies to the labels only).
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy } from 'lucide-react'
import { ApiError } from '@/lib/api'
import { allFailures, diagnosticBundle, type FailureRecord } from '@/lib/diagnostics'
import { cn } from '@/lib/cn'

/** The recorded failure matching this error (richest context: token state, company selection). */
function matchingRecord(err: ApiError): FailureRecord | null {
  const list = allFailures()
  for (let i = list.length - 1; i >= 0; i--) {
    const f = list[i]
    if (f.method === err.method && f.path === err.path && f.status === err.status) return f
  }
  return null
}

export function ErrorDetails({ error, className }: { error: unknown; className?: string }) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  if (!(error instanceof ApiError)) return null

  async function copyBundle(err: ApiError) {
    try {
      await navigator.clipboard.writeText(diagnosticBundle(matchingRecord(err)))
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard unavailable (permissions / non-secure context) — the details stay visible.
    }
  }

  return (
    <div
      className={cn(
        'rounded-lg border border-line bg-paper px-3 py-2 text-left',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 font-mono text-[11px] leading-relaxed text-ink-3">
          <div className="truncate font-semibold text-ink-2">
            {error.path ? `${error.method} ${error.path} → ${error.status}` : `HTTP ${error.status}`}
          </div>
          {error.problem?.type ? <div className="truncate">{error.problem.type}</div> : null}
          {error.problem?.detail ? <div className="break-words">{error.problem.detail}</div> : null}
          {error.traceId ? (
            <div className="truncate">
              {t('common.traceId')}: {error.traceId}
            </div>
          ) : null}
        </div>
        <button
          type="button"
          onClick={() => copyBundle(error)}
          className={cn(
            'flex shrink-0 items-center gap-1.5 rounded-md border border-line bg-surface px-2 py-1',
            'text-[11px] font-semibold text-ink-2 transition-colors hover:text-ink',
            'focus-visible:outline-2 focus-visible:outline-brand-500',
          )}
        >
          {copied ? <Check className="size-3 text-profit" /> : <Copy className="size-3" />}
          {copied ? t('common.copied') : t('common.copyDetails')}
        </button>
      </div>
    </div>
  )
}
