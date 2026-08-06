import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Bluetooth, Cable, Check, Printer, TriangleAlert, Usb } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { usePrinter } from '@/lib/escpos/printerContext'
import type { PaperWidth } from '@/lib/escpos/receipt'
import { classifyConnectError, type TransportKind } from '@/lib/escpos/transport'

/**
 * Printer settings (ADR 0039) — connect a thermal ESC/POS printer over USB / Bluetooth / Serial
 * and print a test receipt. The connection is a per-DEVICE (till) preference, not per-tenant. All
 * copy via i18n (rule 9); the browser owns the device grant, we persist only reconnection hints.
 */
export function PrinterSettings() {
  const { t } = useTranslation()
  const printer = usePrinter()
  const [paper, setPaper] = useState<PaperWidth>(printer.config?.paper ?? 80)
  const [error, setError] = useState<string | null>(null)
  const [testState, setTestState] = useState<'idle' | 'printing' | 'ok' | 'fail'>('idle')

  const transports: { kind: TransportKind; icon: typeof Usb; labelKey: string; hintKey: string }[] =
    [
      { kind: 'usb', icon: Usb, labelKey: 'settings.printer.usb', hintKey: 'settings.printer.usbHint' },
      {
        kind: 'ble',
        icon: Bluetooth,
        labelKey: 'settings.printer.bluetooth',
        hintKey: 'settings.printer.bluetoothHint',
      },
      {
        kind: 'serial',
        icon: Cable,
        labelKey: 'settings.printer.serial',
        hintKey: 'settings.printer.serialHint',
      },
    ]

  const connect = async (kind: TransportKind) => {
    setError(null)
    try {
      await printer.connect(kind, paper, printer.config?.drawerKick ?? false)
    } catch (err) {
      // Map the browser's DOMException to a specific reason. A cancelled chooser is not worth an
      // alarming banner — just clear the connecting state silently.
      const reason = classifyConnectError(err)
      if (reason === 'cancelled') return
      setError(t(`settings.printer.error.${reason}`))
    }
  }

  const testPrint = async () => {
    setTestState('printing')
    const bytesOk = await printer.printReceipt({
      businessName: t('settings.printer.testBusiness'),
      title: t('settings.printer.testTitle'),
      reference: 'TEST-0001',
      dateTime: new Date().toLocaleString(),
      metaRows: [{ label: t('settings.printer.testRow'), valueLabel: t('settings.printer.testRowValue') }],
      lineItems: [
        {
          qty: 2,
          name: t('settings.printer.testItem'),
          priceLabel: 'Rp 50.000',
          modifiers: [{ label: t('settings.printer.testModifier'), deltaLabel: '+Rp 2.000' }],
        },
      ],
      totalRows: [{ label: t('pos.receipt.subtotal'), valueLabel: 'Rp 52.000' }],
      grandTotalLabel: 'Rp 52.000',
      grandTotalCaption: t('pos.receipt.total'),
      paymentRows: [{ label: t('pos.receipt.tender'), valueLabel: t('settings.printer.testTender') }],
      footerNote: t('settings.printer.testFooter'),
    })
    setTestState(bytesOk ? 'ok' : 'fail')
    setTimeout(() => setTestState('idle'), 2500)
  }

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
          {t('settings.printer.title')}
        </h1>
        <p className="mt-1.5 text-[15px] text-ink-3">{t('settings.printer.subtitle')}</p>
      </div>

      {printer.connected ? (
        <Card className="flex flex-wrap items-center justify-between gap-3 border-profit/30 bg-profit-tint/40 p-5">
          <div className="flex items-center gap-2.5 text-sm text-ink">
            <span className="grid size-9 place-items-center rounded-full bg-profit/15 text-profit-ink">
              <Check className="size-4" />
            </span>
            <div>
              <div className="font-semibold">{t('settings.printer.connected')}</div>
              <div className="text-ink-3">
                {printer.config?.label ?? printer.config?.transport} · {printer.config?.paper}mm
              </div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" onClick={testPrint} disabled={testState === 'printing'}>
              <Printer className="size-4" />
              {testState === 'printing'
                ? t('settings.printer.testPrinting')
                : testState === 'ok'
                  ? t('settings.printer.testOk')
                  : testState === 'fail'
                    ? t('settings.printer.testFail')
                    : t('settings.printer.testPrint')}
            </Button>
            <Button variant="outline" onClick={() => printer.disconnect()}>
              {t('settings.printer.disconnect')}
            </Button>
          </div>
        </Card>
      ) : (
        <Card className="p-5">
          <div className="mb-3 text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
            {t('settings.printer.paperWidth')}
          </div>
          <div className="flex gap-2">
            {([58, 80] as const).map((w) => (
              <button
                key={w}
                type="button"
                onClick={() => setPaper(w)}
                className={`rounded-lg border px-4 py-2 text-sm font-semibold transition-colors ${
                  paper === w
                    ? 'border-brand-500 bg-brand-50 text-brand-700'
                    : 'border-line text-ink-2 hover:bg-hover'
                }`}
              >
                {w}mm
              </button>
            ))}
          </div>
        </Card>
      )}

      {!printer.connected ? (
        <div className="grid gap-4 sm:grid-cols-3">
          {transports.map(({ kind, icon: Icon, labelKey, hintKey }) => {
            const supported = printer.support[kind]
            return (
              <Card key={kind} className="flex flex-col gap-3 p-5">
                <div className="flex items-center gap-2.5">
                  <span className="grid size-9 place-items-center rounded-xl bg-brand-50 text-brand-700">
                    <Icon className="size-4" />
                  </span>
                  <span className="font-semibold text-ink">{t(labelKey)}</span>
                </div>
                <p className="flex-1 text-sm text-ink-3">{t(hintKey)}</p>
                <Button
                  variant="outline"
                  disabled={!supported || printer.connectingLabel === kind}
                  onClick={() => connect(kind)}
                >
                  {printer.connectingLabel === kind
                    ? t('settings.printer.connecting')
                    : supported
                      ? t('settings.printer.connect')
                      : t('settings.printer.unsupported')}
                </Button>
              </Card>
            )
          })}
        </div>
      ) : null}

      {error ? (
        <Card className="flex items-start gap-2 p-4 text-sm text-loss">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <span>{error}</span>
        </Card>
      ) : null}

      <p className="text-xs text-ink-3">{t('settings.printer.compatNote')}</p>
    </div>
  )
}
