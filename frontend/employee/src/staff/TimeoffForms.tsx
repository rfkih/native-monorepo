/**
 * Leave-request and overtime sheet forms for the staff app (ADR 0049 P5 redesign — "dialogs become
 * sheets"). Lifted from the console's MyTimeoff inline dialogs (which are not exported) and reused
 * verbatim over DialogOverlay — the SAME phone bottom-sheet primitive the reused NewClaimDialog uses,
 * so all three create flows look consistent. Same create hooks, same validation, same i18n keys.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { DialogOverlay } from '@/features/org/parts'
import { cn } from '@/lib/cn'
import { useCreateLeaveRequest, useCreateOvertimeEntry } from '@/features/me/api'

export function LeaveRequestSheet({
  companyId,
  actor,
  onClose,
}: {
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [leaveType, setLeaveType] = useState<'ANNUAL' | 'UNPAID' | 'SICK'>('ANNUAL')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [days, setDays] = useState('1')
  const create = useCreateLeaveRequest({ companyId, actor })
  // A range spanning two calendar months is rejected 422 — surfaced distinctly (split into two).
  const crossMonth = (create.error as { status?: number } | null)?.status === 422

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const parsedDays = Number.parseInt(days, 10)
    if (!startDate || !endDate || Number.isNaN(parsedDays) || parsedDays <= 0) return
    create.mutate(
      { body: { leaveType, startDate, endDate, days: parsedDays }, idempotencyKey: crypto.randomUUID() },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('me.timeoff.requestLeave')}</h2>
        <Field label={t('me.timeoff.leaveType')} htmlFor="rl-type">
          <div className="flex gap-2">
            {(['ANNUAL', 'UNPAID', 'SICK'] as const).map((lt) => (
              <button
                key={lt}
                type="button"
                onClick={() => setLeaveType(lt)}
                aria-pressed={leaveType === lt}
                className={cn(
                  'flex-1 rounded-xl border px-3 py-2.5 text-sm font-semibold',
                  leaveType === lt
                    ? 'border-emerald bg-emerald-tint text-emerald-2'
                    : 'border-line text-ink-2 hover:bg-hover',
                )}
              >
                {t(`attendance.leaveType.${lt}`)}
              </button>
            ))}
          </div>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('me.timeoff.startDate')} htmlFor="rl-start">
            <TextInput
              id="rl-start"
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              required
            />
          </Field>
          <Field label={t('me.timeoff.endDate')} htmlFor="rl-end">
            <TextInput
              id="rl-end"
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              required
            />
          </Field>
        </div>
        <Field label={t('me.timeoff.days')} htmlFor="rl-days">
          <TextInput
            id="rl-days"
            type="number"
            min={1}
            value={days}
            onChange={(e) => setDays(e.target.value)}
            required
          />
        </Field>
        {create.isError ? (
          <p className="text-sm text-loss">
            {crossMonth ? t('me.timeoff.crossMonthError') : t('attendance.decide.error')}
          </p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? t('hr.form.saving') : t('common.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

export function OvertimeSheet({
  companyId,
  actor,
  onClose,
}: {
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [workDate, setWorkDate] = useState('')
  const [minutes, setMinutes] = useState('60')
  const [dayKind, setDayKind] = useState<'WEEKDAY' | 'REST_DAY'>('WEEKDAY')
  const create = useCreateOvertimeEntry({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const parsedMinutes = Number.parseInt(minutes, 10)
    if (!workDate || Number.isNaN(parsedMinutes) || parsedMinutes <= 0) return
    create.mutate(
      { body: { workDate, minutes: parsedMinutes, dayKind }, idempotencyKey: crypto.randomUUID() },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('me.timeoff.logOvertime')}</h2>
        <Field label={t('me.timeoff.workDate')} htmlFor="ot-date">
          <TextInput
            id="ot-date"
            type="date"
            value={workDate}
            onChange={(e) => setWorkDate(e.target.value)}
            required
          />
        </Field>
        <Field label={t('me.timeoff.minutes')} htmlFor="ot-minutes">
          <TextInput
            id="ot-minutes"
            type="number"
            min={1}
            max={600}
            value={minutes}
            onChange={(e) => setMinutes(e.target.value)}
            required
          />
        </Field>
        <Field label={t('me.timeoff.dayKind')} htmlFor="ot-kind">
          <div className="flex gap-2">
            {(['WEEKDAY', 'REST_DAY'] as const).map((k) => (
              <button
                key={k}
                type="button"
                onClick={() => setDayKind(k)}
                aria-pressed={dayKind === k}
                className={cn(
                  'flex-1 rounded-xl border px-3 py-2.5 text-sm font-semibold',
                  dayKind === k
                    ? 'border-emerald bg-emerald-tint text-emerald-2'
                    : 'border-line text-ink-2 hover:bg-hover',
                )}
              >
                {t(`attendance.dayKind.${k}`)}
              </button>
            ))}
          </div>
        </Field>
        {create.isError ? <p className="text-sm text-loss">{t('attendance.decide.error')}</p> : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? t('hr.form.saving') : t('common.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
