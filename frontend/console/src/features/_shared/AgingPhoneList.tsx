/**
 * AgingPhoneList — the phone rendering of an AR/AP aging report (Native Console Android
 * design): the 7-column desktop table becomes one card per party — name + outstanding, a
 * bucket-distribution bar, and chips for the non-zero buckets. Bucket index 4 (90+) carries
 * the loss tone, 2–3 (31–90) the warning tone, matching the desktop table's cell tones.
 */
import { Card } from '@/components/ui/Card'
import { cn } from '@/lib/cn'
import { formatAmount, formatMoney } from '@/lib/money'

export interface AgingPhoneRow {
  id: string
  name: string
  /** The five bucket amounts in display order: current, 1–30, 31–60, 61–90, 90+. */
  buckets: number[]
  outstandingMinor: number
}

function chipClass(bucketIndex: number): string {
  if (bucketIndex === 4) return 'bg-tint-loss text-loss'
  if (bucketIndex >= 2) return 'bg-amber-tint text-amber-2'
  return 'bg-ink-50 text-ink-2'
}

function segmentClass(bucketIndex: number): string {
  if (bucketIndex === 4) return 'bg-loss'
  if (bucketIndex >= 2) return 'bg-warning'
  return 'bg-emerald'
}

export function AgingPhoneList({
  rows,
  labels,
  currency,
  locale,
}: {
  rows: AgingPhoneRow[]
  /** The five bucket column labels, already translated. */
  labels: string[]
  currency: string
  locale: string
}) {
  return (
    <div className="flex flex-col gap-2">
      {rows.map((r) => {
        const bucketSum = r.buckets.reduce((a, v) => a + v, 0)
        return (
          <Card key={r.id} className="p-3.5">
            <div className="flex items-baseline justify-between gap-3">
              <span className="min-w-0 truncate text-[14px] font-bold text-ink">{r.name}</span>
              <span className="tnum shrink-0 font-mono text-[14.5px] font-bold text-ink">
                {formatMoney(r.outstandingMinor, currency, locale)}
              </span>
            </div>
            {bucketSum > 0 ? (
              <div className="mt-2 flex h-1 overflow-hidden rounded-full bg-ink-50" aria-hidden="true">
                {r.buckets.map((v, i) =>
                  v > 0 ? (
                    <span
                      key={labels[i]}
                      className={segmentClass(i)}
                      style={{ width: `${(v / bucketSum) * 100}%` }}
                    />
                  ) : null,
                )}
              </div>
            ) : null}
            <div className="mt-2.5 flex flex-wrap gap-1.5">
              {r.buckets.map((v, i) =>
                v > 0 ? (
                  <span
                    key={labels[i]}
                    className={cn(
                      'tnum inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 font-mono text-[11.5px] font-semibold',
                      chipClass(i),
                    )}
                  >
                    <span className="font-sans font-semibold opacity-70">{labels[i]}</span>
                    {formatAmount(v, currency, locale)}
                  </span>
                ) : null,
              )}
            </div>
          </Card>
        )
      })}
    </div>
  )
}
