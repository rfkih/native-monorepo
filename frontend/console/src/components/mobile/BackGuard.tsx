/**
 * BackGuard — mount ONCE inside each app's authenticated tree (console App.tsx, employee App.tsx).
 * Renders nothing outside the Android shells (useBackGuard self-disables). See useBackGuard.ts for
 * the mechanism; this component only hosts the dialog and the exit-fallback hint.
 */
import { useTranslation } from 'react-i18next'
import { useBackGuard } from './useBackGuard'
import { LeaveConfirmDialog } from './LeaveConfirmDialog'

export function BackGuard({ homePath }: { homePath: string }) {
  const { t } = useTranslation()
  const { dialog, hintVisible, cancel, confirmLeave, confirmExit } = useBackGuard(homePath)

  return (
    <>
      {dialog !== null ? (
        <LeaveConfirmDialog
          kind={dialog}
          onConfirm={dialog === 'exit' ? confirmExit : confirmLeave}
          onCancel={cancel}
        />
      ) : null}
      {hintVisible ? (
        <div
          className="fixed inset-x-0 bottom-[calc(1.5rem+var(--safe-area-inset-bottom,0px))] z-[100] flex justify-center px-4"
          role="status"
        >
          <span className="rounded-full bg-ink px-4 py-2 text-sm font-medium text-surface shadow-lg">
            {t('backGuard.exitHint')}
          </span>
        </div>
      ) : null}
    </>
  )
}
