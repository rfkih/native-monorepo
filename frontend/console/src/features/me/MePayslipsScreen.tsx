/**
 * MePayslipsScreen — the phone-only /me/payslips screen (Native Console Android design):
 * PhoneScreen chrome around the shared PayslipsSection (YTD card + run list + sign-flip
 * detail — extracted verbatim from Me.tsx). At tablet width and up the payslips live inline
 * on /me, so this route bounces there.
 */

import { useTranslation } from 'react-i18next'
import { Navigate } from 'react-router-dom'
import { TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Spinner } from '@/components/ui/Spinner'
import { PhoneScreen } from '@/components/mobile/PhoneScreen'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { EmptyState } from '@/features/_shared/financeUi'
import { useAuth } from '@/lib/authContext'
import { localeOf } from '@/i18n'
import { isNotLinked, useMyProfile } from './api'
import { PayslipsSection } from './PayslipsSection'

export function MePayslipsScreen() {
  const { t, i18n } = useTranslation()
  const isPhone = useIsPhone()
  const auth = useAuth()
  const locale = localeOf(i18n.language)
  const companyId = auth.companyId ?? 'me'
  const actor = auth.actor
  const profile = useMyProfile({ companyId, actor, enabled: true })
  const notLinked = isNotLinked(profile.error)

  if (!isPhone) return <Navigate to="/me" replace />

  return (
    <PhoneScreen title={t('me.payslips.title')} backTo="/me">
      {profile.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : notLinked ? (
        <EmptyState title={t('me.notLinked.title')} hint={t('me.notLinked.hint')} />
      ) : profile.isError || !profile.data ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('me.error')}
        </Card>
      ) : (
        <PayslipsSection
          companyId={companyId}
          actor={actor}
          locale={locale}
          employeeName={profile.data.fullName}
        />
      )}
    </PhoneScreen>
  )
}
