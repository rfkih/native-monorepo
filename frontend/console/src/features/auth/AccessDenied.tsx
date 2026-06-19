import { useTranslation } from 'react-i18next'
import { ShieldAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { useAuth } from '@/lib/authContext'

/**
 * Shown to an authenticated principal that holds none of the console's business roles
 * (owner / manager / cashier). Their only action is to sign out and try another account.
 */
export function AccessDenied() {
  const { t } = useTranslation()
  const auth = useAuth()
  return (
    <Card className="mx-auto max-w-md p-10 text-center">
      <div className="mx-auto grid size-12 place-items-center rounded-full bg-amber-tint text-amber-2">
        <ShieldAlert className="size-6" />
      </div>
      <h2 className="mt-4 font-display text-xl font-semibold text-ink">{t('auth.deniedTitle')}</h2>
      <p className="mx-auto mt-2 max-w-sm text-sm text-ink-3">{t('auth.deniedBody')}</p>
      {auth.actor ? (
        <p className="mt-1 text-xs text-ink-3">
          {t('auth.signedInAs')} <span className="font-medium text-ink-2">{auth.actor}</span>
        </p>
      ) : null}
      <Button className="mt-6" onClick={auth.logout}>
        {t('auth.signOut')}
      </Button>
    </Card>
  )
}
