/**
 * OperatorPinDialog — owner/manager set or reset an employee's operator PIN (two-app +
 * outlet-terminal program, ADR 0049 P1): the 4-6 digit code a cashier enters at the till to ring a
 * sale under this employee's name. Write-only by contract: the backend never returns the PIN
 * (rule 6), so this dialog only ever SETS/RESETS, never reveals — there is no matching read hook.
 *
 * The pin/confirm values live in local component state only, are never logged, and are dropped
 * the moment the dialog closes (ordinary React state, nothing to persist).
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { DialogOverlay } from '@/features/org/parts'
import { useSetOperatorPin, type EmployeeListRow } from './api'

const PIN_PATTERN = /^[0-9]{4,6}$/

export function OperatorPinDialog({
  employee,
  companyId,
  actor,
  onClose,
}: {
  employee: EmployeeListRow
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [pin, setPin] = useState('')
  const [confirmPin, setConfirmPin] = useState('')
  const [done, setDone] = useState(false)
  const mutation = useSetOperatorPin({ companyId, actor })

  const valid = PIN_PATTERN.test(pin)
  const mismatch = confirmPin.length > 0 && pin !== confirmPin

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!valid || pin !== confirmPin) return
    mutation.mutate(
      { employeeId: employee.employeeId, pin },
      { onSuccess: () => setDone(true) },
    )
  }

  if (done) {
    return (
      <DialogOverlay onClose={onClose}>
        <div className="space-y-4">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('hr.operatorPin.doneTitle')}
          </h2>
          <p className="text-sm text-ink-2">
            {t('hr.operatorPin.doneBody', { name: employee.fullName })}
          </p>
          <div className="flex justify-end">
            <Button type="button" onClick={onClose}>
              {t('common.close')}
            </Button>
          </div>
        </div>
      </DialogOverlay>
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('hr.operatorPin.title', { name: employee.fullName })}
        </h2>
        <p className="text-sm text-ink-2">{t('hr.operatorPin.body')}</p>

        <Field label={t('hr.operatorPin.pinLabel')} htmlFor="operator-pin">
          <TextInput
            id="operator-pin"
            type="password"
            inputMode="numeric"
            autoComplete="off"
            maxLength={6}
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
            required
            autoFocus
          />
        </Field>

        <Field
          label={t('hr.operatorPin.confirmLabel')}
          htmlFor="operator-pin-confirm"
          error={mismatch ? t('hr.operatorPin.mismatch') : undefined}
        >
          <TextInput
            id="operator-pin-confirm"
            type="password"
            inputMode="numeric"
            autoComplete="off"
            maxLength={6}
            value={confirmPin}
            onChange={(e) => setConfirmPin(e.target.value.replace(/\D/g, ''))}
            required
          />
        </Field>

        {pin.length > 0 && !valid ? (
          <p className="text-sm text-loss">{t('hr.operatorPin.invalid')}</p>
        ) : mutation.isError ? (
          <p className="text-sm text-loss">{t('hr.operatorPin.error')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !valid || pin !== confirmPin}>
            {mutation.isPending ? t('hr.operatorPin.submitting') : t('hr.operatorPin.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
