/**
 * PayslipsScreen — the Native Karyawan "Slip gaji" tab (ADR 0049 P5 redesign): a dark year-to-date
 * card (gross + PPh 21 withheld, Track P Phase P10's client-summed figure) above an expandable list
 * of the caller's own payslip runs. Each row opens into a gross/deduction/net tile row + the line
 * items + a "Cetak slip" print action — reusing the SAME line-label mapping, sign-flip logic, and
 * `PayslipPrint` document the console's `PayslipsSection`/`MePayslipsScreen` use (never re-derive
 * payslip-line semantics — see that file's comment on the deduction sign flip). Amounts are the
 * caller's own real figures (rule 6 masking applies to NIK/bank, not pay).
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronRight, Printer, TriangleAlert } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import { PayslipPrint } from '@/features/_shared/PayslipPrint'
import {
  isNotLinked,
  useMyPayslip,
  useMyPayslips,
  useMyPayslipsYtd,
  useMyProfile,
  type MyPayslipHeader,
  type MyPayslipLine,
} from '@/features/me/api'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import { localeOf } from '@/i18n'
import { useSession } from '@/lib/session'
import { StaffHeader, useTenant } from './ui'

export function PayslipsScreen() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const { companyId, actor } = useTenant()
  const { company } = useSession()
  const year = new Date().getFullYear()

  const profile = useMyProfile({ companyId, actor, enabled: true })
  const notLinked = isNotLinked(profile.error)
  const payslips = useMyPayslips({ companyId, actor, enabled: true })
  const ytd = useMyPayslipsYtd({ companyId, actor, year, enabled: true })

  const [openIds, setOpenIds] = useState<Set<string>>(new Set())
  const toggle = (runId: string) =>
    setOpenIds((cur) => {
      const next = new Set(cur)
      if (next.has(runId)) next.delete(runId)
      else next.add(runId)
      return next
    })

  if (profile.isLoading || payslips.isLoading) {
    return (
      <>
        <StaffHeader title={t('staff.payslips.title')} />
        <div className="flex flex-col gap-3 p-4">
          <Skeleton className="h-[124px] rounded-[20px]" />
          <ListSkeleton rows={4} />
        </div>
      </>
    )
  }

  if (notLinked) {
    return (
      <>
        <StaffHeader title={t('staff.payslips.title')} />
        <div className="p-4 pt-6">
          <EmptyState title={t('me.notLinked.title')} hint={t('me.notLinked.hint')} />
        </div>
      </>
    )
  }

  if (profile.isError || !profile.data || payslips.isError) {
    return (
      <>
        <StaffHeader title={t('staff.payslips.title')} />
        <Card className="m-4 mt-6 p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('me.error')}
        </Card>
      </>
    )
  }

  const employeeName = profile.data.fullName
  const ytdCurrency = ytd.currency ?? company?.baseCurrency ?? 'IDR'
  const sorted = [...(payslips.data ?? [])].sort((a, b) => b.period.localeCompare(a.period))

  return (
    <>
      <StaffHeader title={t('staff.payslips.title')} />
      <div className="flex flex-col gap-3 p-4">
        {/* Year-to-date */}
        <div className="rounded-[20px] bg-[#0e1116] p-[18px]">
          <div className="font-mono text-[11px] font-semibold uppercase tracking-[0.06em] text-white/45">
            {t('staff.payslips.ytdTitle', { year })}
          </div>
          <div className="mt-3 flex">
            <div className="flex-1 pr-4">
              <div className="text-[12px] font-medium text-white/55">{t('staff.payslips.gross')}</div>
              {ytd.loading ? (
                // Not the shared `<Skeleton>` here — its own `bg-ink-100` and this `bg-white/10`
                // are equal-specificity Tailwind utilities, so which one wins depends on
                // generated-stylesheet order, not JSX order; a plain pulse div avoids that trap.
                <div aria-hidden="true" className="mt-1.5 h-6 w-24 animate-pulse rounded bg-white/10" />
              ) : (
                <div className="tnum mt-1 font-mono text-[21px] font-bold leading-none text-white">
                  {formatMoney(ytd.grossMinor, ytdCurrency, locale)}
                </div>
              )}
            </div>
            <div className="w-px shrink-0 bg-white/10" />
            <div className="flex-1 pl-4">
              <div className="text-[12px] font-medium text-white/55">{t('staff.payslips.pph21')}</div>
              {ytd.loading ? (
                <div aria-hidden="true" className="mt-1.5 h-6 w-24 animate-pulse rounded bg-white/10" />
              ) : (
                <div className="tnum mt-1 font-mono text-[21px] font-bold leading-none text-white">
                  {formatMoney(ytd.pph21Minor, ytdCurrency, locale)}
                </div>
              )}
            </div>
          </div>
          <p className="mt-3 text-[11.5px] leading-snug text-white/40">
            {t('staff.payslips.ytdHint', { count: ytd.runCount })}
          </p>
        </div>

        {/* Payslip runs */}
        {sorted.length === 0 ? (
          <p className="pt-2 text-center text-[13px] text-ink-3">{t('me.payslips.empty')}</p>
        ) : (
          <div className="flex flex-col gap-2.5">
            {sorted.map((header) => (
              <PayslipRow
                key={header.runId}
                header={header}
                open={openIds.has(header.runId)}
                onToggle={() => toggle(header.runId)}
                companyId={companyId}
                actor={actor}
                locale={locale}
                employeeName={employeeName}
                companyName={company?.name ?? ''}
              />
            ))}
          </div>
        )}

        <p className="px-2 pt-2 text-center text-[11.5px] leading-snug text-ink-3">
          {t('staff.payslips.footerHint')}
        </p>
      </div>
    </>
  )
}

/** 'YYYY-MM' → a localized "Month YYYY" label (e.g. "Agustus 2026" / "August 2026"). */
function periodLabel(period: string, locale: string): string {
  const d = new Date(`${period}-01T00:00:00`)
  if (Number.isNaN(d.getTime())) return period
  return new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' }).format(d)
}

function PayslipRow({
  header,
  open,
  onToggle,
  companyId,
  actor,
  locale,
  employeeName,
  companyName,
}: {
  header: MyPayslipHeader
  open: boolean
  onToggle: () => void
  companyId: string
  actor: string
  locale: string
  employeeName: string
  companyName: string
}) {
  const { t } = useTranslation()
  const [showPrint, setShowPrint] = useState(false)
  // Enabled unconditionally (not gated on `open`) so the COLLAPSED row can already show the net
  // amount — the list is small (one run per period), so fetching every row's detail up front is
  // cheap, and it means opening a row never has to wait on a query that already resolved.
  const detail = useMyPayslip({ companyId, actor, runId: header.runId, enabled: true })

  return (
    <Card className="overflow-hidden rounded-[18px]">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        // Negative outline-offset (not the usual +2 outward one) — this button is flush against
        // the parent Card's edges (needed for the rounded corners to clip the border-t divider
        // below), so an outward ring would itself get clipped by that same `overflow-hidden`.
        className="flex w-full items-center gap-3 p-4 text-left transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand-500"
      >
        <div className="min-w-0 flex-1">
          <div className="text-[15px] font-bold leading-tight text-ink">
            {periodLabel(header.period, locale)}
          </div>
          {header.runSeq > 1 || header.illustrative ? (
            <div className="mt-1 flex flex-wrap items-center gap-1.5">
              {header.runSeq > 1 ? (
                <span className="text-[11.5px] text-ink-3">
                  {t('me.payslips.runSeq', { seq: header.runSeq })}
                </span>
              ) : null}
              {header.illustrative ? <Badge tone="amber">{t('me.payslips.illustrative')}</Badge> : null}
            </div>
          ) : null}
        </div>
        {detail.isLoading ? (
          <Skeleton className="h-[18px] w-16 shrink-0" />
        ) : (
          <span className="tnum shrink-0 font-mono text-[15px] font-bold text-ink">
            {detail.data ? formatMoney(detail.data.netMinor, detail.data.currency, locale) : '—'}
          </span>
        )}
        {open ? (
          <ChevronDown className="size-[18px] shrink-0 text-ink-300" aria-hidden />
        ) : (
          <ChevronRight className="size-[18px] shrink-0 text-ink-300" aria-hidden />
        )}
      </button>

      {open ? (
        <div className="border-t border-line px-4 pb-4 pt-3.5">
          {detail.isLoading ? (
            <div className="flex flex-col gap-2.5">
              <div className="grid grid-cols-3 gap-2">
                <Skeleton className="h-16 rounded-[14px]" />
                <Skeleton className="h-16 rounded-[14px]" />
                <Skeleton className="h-16 rounded-[14px]" />
              </div>
              <Skeleton className="h-24 rounded-[14px]" />
            </div>
          ) : detail.isError || !detail.data ? (
            <p className="text-[13px] text-loss">{t('me.error')}</p>
          ) : (
            <>
              <div className="grid grid-cols-3 gap-2">
                <PayslipTile
                  label={t('staff.payslips.gross')}
                  minor={detail.data.grossMinor}
                  currency={detail.data.currency}
                  locale={locale}
                />
                <PayslipTile
                  label={t('staff.payslips.deductions')}
                  minor={detail.data.deductionMinor}
                  currency={detail.data.currency}
                  locale={locale}
                />
                <PayslipTile
                  label={t('staff.payslips.received')}
                  minor={detail.data.netMinor}
                  currency={detail.data.currency}
                  locale={locale}
                  tint
                />
              </div>

              <div className="mt-3">
                {detail.data.lines.map((line, i) => (
                  <PayslipLineRow key={`${line.componentKey}-${i}`} line={line} locale={locale} />
                ))}
              </div>

              <Button
                type="button"
                variant="outline"
                className="mt-3.5 w-full"
                onClick={() => setShowPrint(true)}
              >
                <Printer className="size-4" />
                {t('staff.payslips.print')}
              </Button>
            </>
          )}
        </div>
      ) : null}

      {showPrint && detail.data ? (
        <PayslipPrint
          companyName={companyName}
          employeeName={employeeName}
          period={detail.data.period}
          runSeq={detail.data.runSeq}
          runType={detail.data.runType}
          currency={detail.data.currency}
          grossMinor={detail.data.grossMinor}
          deductionMinor={detail.data.deductionMinor}
          netMinor={detail.data.netMinor}
          illustrative={detail.data.illustrative}
          lines={detail.data.lines}
          locale={locale}
          onClose={() => setShowPrint(false)}
        />
      ) : null}
    </Card>
  )
}

/** One Bruto/Potongan/Diterima tile — `tint` marks the "Diterima" (net) tile a profit tone. */
function PayslipTile({
  label,
  minor,
  currency,
  locale,
  tint,
}: {
  label: string
  minor: number
  currency: string
  locale: string
  tint?: boolean
}) {
  return (
    <div className={cn('rounded-[14px] px-3 py-2.5', tint ? 'bg-tint-profit' : 'bg-paper')}>
      <div className={cn('text-[10.5px] font-medium', tint ? 'text-profit-ink' : 'text-ink-3')}>
        {label}
      </div>
      <div
        className={cn(
          'tnum mt-1 font-mono text-[13.5px] font-bold leading-tight',
          tint ? 'text-profit-ink' : 'text-ink',
        )}
      >
        {formatMoney(minor, currency, locale)}
      </div>
    </div>
  )
}

/**
 * A single payslip line — SAME label mapping + deduction sign flip as `PayslipsSection`'s table row
 * (do not re-derive): `payslip.components.<key>` resolves the component name (raw-key fallback), a
 * DEDUCTION is shown negated, and a strictly positive displayed deduction is a true-up credit back
 * to the employee, not a double-negative rendering bug.
 */
function PayslipLineRow({ line, locale }: { line: MyPayslipLine; locale: string }) {
  const { t } = useTranslation()
  const displayMinor = line.kind === 'DEDUCTION' ? -line.amountMinor : line.amountMinor
  const isCredit = line.kind === 'DEDUCTION' && displayMinor > 0
  return (
    <div className="flex items-center justify-between gap-3 border-b border-line py-2 last:border-b-0">
      <div className="min-w-0">
        <div className="truncate text-[13px] font-medium text-ink">
          {t(`payslip.components.${line.componentKey}` as Parameters<typeof t>[0], {
            defaultValue: line.componentKey,
          })}
        </div>
        <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[11px] text-ink-3">
          <span>{t(line.kind === 'EARNING' ? 'me.payslips.earning' : 'me.payslips.deduction')}</span>
          {line.bearer === 'EMPLOYER' ? <span>({t('me.payslips.employer')})</span> : null}
          {line.illustrative ? <Badge tone="amber">{t('me.payslips.illustrative')}</Badge> : null}
          {isCredit ? <Badge tone="profit">{t('me.payslips.trueUpCredit')}</Badge> : null}
        </div>
      </div>
      <span
        className={cn(
          'tnum shrink-0 font-mono text-[13px] font-semibold',
          line.kind !== 'DEDUCTION' ? 'text-ink' : isCredit ? 'text-profit-ink' : 'text-loss',
        )}
      >
        {formatMoney(displayMinor, line.currency, locale)}
      </span>
    </div>
  )
}
