/**
 * ScreenHeader — the 56px phone screen header (Native Console Android design):
 * a 44px back target (pop-with-fallback, or a callback), a bold truncating title, and an
 * optional trailing slot (actions). Sticky over the scrolling screen body.
 *
 * `backFallback` is a FALLBACK, not the destination: the arrow pops history and only lands there
 * when there is nothing to pop (BackButton / rule N1). It used to be `backTo` — a plain `<Link>` —
 * which pushed an entry on every press and inflated the history stack.
 */
import { ChevronLeft } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/cn'
import { BackButton } from './BackButton'

const BACK_CLASS =
  'grid size-11 shrink-0 place-items-center rounded-full text-ink hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald'

export function ScreenHeader({
  title,
  backFallback,
  onBack,
  trailing,
  className,
}: {
  /** Already-translated title. */
  title: string
  /** Route to land on when history has nothing to pop (deep link / cold open). */
  backFallback?: string
  /** …or take the back press entirely (overlays close themselves). Wins over `backFallback`. */
  onBack?: () => void
  trailing?: React.ReactNode
  /** Extra classes on the header element (e.g. `sm:hidden` for phone-only chrome). */
  className?: string
}) {
  const { t } = useTranslation()

  return (
    <header
      className={cn(
        // Solid, not translucent — the blur cost a compositing layer on every scroll for very
        // little separation. It stays `bg-paper`, NOT `bg-surface`: PhoneScreen renders on paper,
        // and on dark those differ (#101010 vs #181818), so surface would paint the sticky header
        // as a lighter block floating above its own page.
        'sticky top-0 z-20 flex h-14 items-center gap-1 border-b border-line bg-paper px-2',
        className,
      )}
    >
      {onBack != null ? (
        <button type="button" onClick={onBack} aria-label={t('common.back')} className={BACK_CLASS}>
          <ChevronLeft className="size-[22px]" aria-hidden />
        </button>
      ) : backFallback != null ? (
        <BackButton fallback={backFallback} className={BACK_CLASS} />
      ) : null}
      <h1 className="min-w-0 flex-1 truncate pl-1 text-[17px] font-bold text-ink">{title}</h1>
      {trailing != null ? <div className="flex shrink-0 items-center gap-1 pr-1">{trailing}</div> : null}
    </header>
  )
}
