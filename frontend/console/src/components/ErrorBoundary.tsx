/**
 * ErrorBoundary — the app-global crash screen. Without one, ANY uncaught render error unmounts
 * the React root and leaves a permanently blank page; in the Android shells there is not even a
 * reload affordance, so the user's only exit is force-closing the app. This boundary keeps the
 * failure visible and recoverable (reload button).
 *
 * Mounted as the OUTERMOST element of each app's tree (console + employee main.tsx), i.e. above
 * every provider — so a provider crash is caught too. Because the tree below it (including the
 * react-i18next provider) may be the thing that crashed, copy goes through the module-level
 * `i18n.t` (works without React context); the keys live in the normal locale files (rule 9).
 */
import { Component, type ErrorInfo, type ReactNode } from 'react'
import i18n from '@/i18n'

export class ErrorBoundary extends Component<{ children: ReactNode }, { crashed: boolean }> {
  state = { crashed: false }

  static getDerivedStateFromError(): { crashed: boolean } {
    return { crashed: true }
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    // The render stack is the one diagnostic the redbox-less production build would swallow.
    console.error('[ErrorBoundary]', error, info.componentStack)
  }

  render() {
    if (!this.state.crashed) return this.props.children
    return (
      <div className="grid min-h-[100dvh] place-items-center bg-paper p-6">
        <div className="w-full max-w-sm rounded-[20px] border border-line bg-surface p-6 text-center shadow-sm">
          <h1 className="font-display text-lg font-semibold text-ink">{i18n.t('appCrash.title')}</h1>
          <p className="mt-2 text-sm text-ink-3">{i18n.t('appCrash.body')}</p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="mt-5 w-full rounded-xl bg-emerald py-2.5 text-sm font-semibold text-on-emerald transition-colors hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            {i18n.t('appCrash.reload')}
          </button>
        </div>
      </div>
    )
  }
}
