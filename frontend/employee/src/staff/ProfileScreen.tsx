/**
 * ProfileScreen — "Profil saya" (ADR 0049 P5 redesign). A PUSHED screen reached from the Beranda
 * avatar (no bottom tab bar): identity, the same masked personal-data row set as the console's
 * MeHomePhone (rule 6 — PII stays masked, never logged), assignments, the language switcher (no
 * dashboard currency/language TOGGLE — this is the per-user profile setting), and account/sign-out.
 * Reuses the console's /me hooks unchanged.
 */
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { ChevronLeft, ChevronRight, LogOut, TriangleAlert, UserRound } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Skeleton } from '@/components/ui/Skeleton'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { EmptyState } from '@/features/_shared/financeUi'
import { formatDate } from '@/features/expenses/format'
import { isNotLinked, useMyProfile, type MeProfile } from '@/features/me/api'
import { useAuth } from '@/lib/authContext'
import { localeOf } from '@/i18n'
import { InfoTip, SectionLabel, useTenant } from './ui'

export function ProfileScreen() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const auth = useAuth()
  const { companyId, actor } = useTenant()

  const profile = useMyProfile({ companyId, actor, enabled: true })
  const notLinked = isNotLinked(profile.error)

  return (
    <div className="min-h-[100dvh] bg-paper">
      <header className="sticky top-0 z-20 flex h-14 items-center gap-1 border-b border-line bg-surface px-2">
        <button
          type="button"
          onClick={() => navigate('/me')}
          aria-label={t('common.back')}
          className="grid size-11 shrink-0 place-items-center rounded-full text-ink transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-brand-500"
        >
          <ChevronLeft className="size-[22px]" aria-hidden />
        </button>
        <h1 className="min-w-0 flex-1 truncate pl-1 text-[17px] font-bold text-ink">
          {t('staff.profile.title')}
        </h1>
      </header>

      {profile.isLoading ? (
        <div className="flex flex-col gap-3 p-4 pt-5">
          <div className="flex items-center gap-3">
            <Skeleton className="size-[58px] shrink-0 rounded-[20px]" />
            <div className="min-w-0 flex-1">
              <Skeleton className="h-5 w-36" />
              <Skeleton className="mt-1.5 h-3.5 w-28" />
            </div>
          </div>
          <Skeleton className="mt-2 h-[220px] rounded-[18px]" />
          <Skeleton className="mt-2 h-8 w-32 rounded-full" />
          <Skeleton className="h-11 w-40 rounded-full" />
        </div>
      ) : notLinked ? (
        <div className="p-4 pt-6">
          <EmptyState title={t('me.notLinked.title')} hint={t('me.notLinked.hint')} />
        </div>
      ) : profile.isError || !profile.data ? (
        <Card className="m-4 mt-6 p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('me.error')}
        </Card>
      ) : (
        <ProfileBody profile={profile.data} onLogout={auth.logout} />
      )}
    </div>
  )
}

/** The loaded screen body — identity, personal data, assignments, language, actions. */
function ProfileBody({ profile: p, onLogout }: { profile: MeProfile; onLogout: () => void }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)

  const subline =
    p.assignments.length > 0 ? p.assignments.map((a) => a.role).join(' · ') : t('me.assignments.empty')

  const rows: { key: string; label: string; value: ReactNode; amber?: boolean; info?: string }[] = [
    {
      key: 'status',
      label: t('me.profile.status'),
      value: (
        <Badge tone={p.status === 'ACTIVE' ? 'profit' : 'amber'}>
          {t(p.status === 'ACTIVE' ? 'me.profile.active' : 'me.profile.inactive')}
        </Badge>
      ),
    },
    { key: 'nik', label: t('me.profile.nik'), value: p.maskedNik },
    { key: 'bank', label: t('me.profile.bank'), value: p.maskedBankAccount },
    p.hasNpwp
      ? { key: 'npwp', label: t('me.profile.npwp'), value: p.maskedNpwp ?? '' }
      : { key: 'npwp', label: t('me.profile.npwp'), value: t('me.profile.npwpNone'), amber: true },
    { key: 'ptkp', label: t('me.profile.ptkp'), value: p.ptkpStatus, info: t('staff.explain.ptkp') },
  ]

  return (
    <>
      {/* Identity */}
      <div className="flex items-center gap-3 px-4 pt-5">
        <span className="grid size-[58px] shrink-0 place-items-center rounded-[20px] bg-brand-50 text-brand-700">
          <UserRound className="size-[28px]" strokeWidth={1.8} aria-hidden />
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[20px] font-bold leading-tight tracking-[-0.015em] text-ink">
            {p.fullName}
          </div>
          <div className="mt-0.5 truncate text-[13px] font-medium text-ink-3">{subline}</div>
        </div>
      </div>

      {/* Personal data (rule 6 — masked, never logged) */}
      <section className="px-4 pt-5">
        <SectionLabel className="pl-1">{t('staff.profile.personalData')}</SectionLabel>
        <Card className="mt-2 rounded-[18px] p-0">
          {rows.map((row) => (
            <div
              key={row.key}
              className="flex min-h-[52px] items-center justify-between gap-3 border-b border-line/60 px-4 py-3 last:border-b-0"
            >
              <span className="flex items-center gap-1.5 text-[13.5px] font-medium text-ink-3">
                {row.label}
                {row.info ? <InfoTip label={row.label} text={row.info} /> : null}
              </span>
              <span
                className={
                  row.amber
                    ? 'text-[13.5px] font-semibold text-amber-2'
                    : 'font-mono text-[13.5px] font-semibold text-ink'
                }
              >
                {row.value}
              </span>
            </div>
          ))}
        </Card>
        <p className="mt-2 pl-1 text-[11.5px] leading-snug text-ink-3">{t('me.home.piiHint')}</p>
      </section>

      {/* Assignments */}
      <section className="px-4 pt-5">
        <SectionLabel className="pl-1">{t('staff.profile.assignments')}</SectionLabel>
        {p.assignments.length > 0 ? (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {p.assignments.map((a) => (
              <span
                key={a.id}
                className="rounded-full border border-line bg-surface px-2.5 py-[5px] text-[12px] font-medium text-ink-3"
              >
                <span className="font-bold text-ink">{a.role}</span>{' '}
                {t('staff.profile.since', { date: formatDate(a.effectiveFrom, locale) })}
              </span>
            ))}
          </div>
        ) : (
          <p className="mt-2 pl-1 text-[13px] text-ink-3">{t('me.assignments.empty')}</p>
        )}
      </section>

      {/* Language — per-user profile setting, never a dashboard toggle */}
      <section className="px-4 pt-5">
        <SectionLabel className="pl-1">{t('staff.profile.language')}</SectionLabel>
        <div className="mt-2">
          <LanguageSwitcher />
        </div>
      </section>

      {/* Actions */}
      <section className="flex flex-col gap-2.5 px-4 pb-8 pt-6">
        <Link
          to="/me/account"
          viewTransition
          className="flex min-h-[52px] items-center justify-between rounded-[16px] border border-line bg-surface px-4 py-3 text-[14px] font-bold text-ink shadow-sm transition-colors hover:border-brand-100 hover:bg-brand-50/40 active:scale-[0.99]"
        >
          {t('staff.profile.account')}
          <ChevronRight className="size-[18px] shrink-0 text-ink-300" aria-hidden />
        </Link>
        <Button
          variant="outline"
          size="lg"
          onClick={onLogout}
          className="w-full border-loss/30 text-loss hover:bg-tint-loss hover:text-loss"
        >
          <LogOut className="size-[18px]" strokeWidth={1.9} aria-hidden />
          {t('staff.profile.logout')}
        </Button>
      </section>
    </>
  )
}
