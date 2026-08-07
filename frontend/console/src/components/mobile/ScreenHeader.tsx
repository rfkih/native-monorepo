/**
 * ScreenHeader — the 56px phone screen header (Native Console Android design):
 * a 44px back target (route link or callback), a bold truncating title, and an
 * optional trailing slot (actions). Sticky over the scrolling screen body.
 */
import { ChevronLeft } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { cn } from '@/lib/cn'

export function ScreenHeader({
  title,
  backTo,
  onBack,
  trailing,
  className,
}: {
  /** Already-translated title. */
  title: string
  /** Back as a route link… */
  backTo?: string
  /** …or as a callback (overlays). */
  onBack?: () => void
  trailing?: React.ReactNode
  /** Extra classes on the header element (e.g. `sm:hidden` for phone-only chrome). */
  className?: string
}) {
  const { t } = useTranslation()
  const backClass =
    'grid size-11 shrink-0 place-items-center rounded-full text-ink hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald'

  return (
    <header
      className={cn(
        'sticky top-0 z-20 flex h-14 items-center gap-1 border-b border-line bg-paper/90 px-2 backdrop-blur',
        className,
      )}
    >
      {backTo != null ? (
        <Link to={backTo} viewTransition aria-label={t('common.back')} className={backClass}>
          <ChevronLeft className="size-[22px]" aria-hidden />
        </Link>
      ) : onBack != null ? (
        <button type="button" onClick={onBack} aria-label={t('common.back')} className={backClass}>
          <ChevronLeft className="size-[22px]" aria-hidden />
        </button>
      ) : null}
      <h1 className="min-w-0 flex-1 truncate pl-1 text-[17px] font-bold text-ink">{title}</h1>
      {trailing != null ? <div className="flex shrink-0 items-center gap-1 pr-1">{trailing}</div> : null}
    </header>
  )
}
