/**
 * MeTimeoffScreen — the phone-only /me/timeoff screen (Native Console Android design):
 * PhoneScreen chrome around the existing MyTimeoff section (balances, request/log dialogs,
 * SUBMITTED-only cancel — all unchanged). At tablet width and up time off lives inline on
 * /me, so this route bounces there.
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
import { isNotLinked, useMyProfile } from './api'
import { MyTimeoff } from './MyTimeoff'

export function MeTimeoffScreen() {
  const { t } = useTranslation()
  const isPhone = useIsPhone()
  const auth = useAuth()
  const companyId = auth.companyId ?? 'me'
  const actor = auth.actor
  const profile = useMyProfile({ companyId, actor, enabled: true })
  const notLinked = isNotLinked(profile.error)

  if (!isPhone) return <Navigate to="/me" replace />

  return (
    <PhoneScreen title={t('me.timeoff.title')} backTo="/me">
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
        <MyTimeoff companyId={companyId} actor={actor} />
      )}
    </PhoneScreen>
  )
}
