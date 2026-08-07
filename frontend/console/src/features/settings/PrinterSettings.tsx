import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Bluetooth, Cable, Check, Printer, Smartphone, TriangleAlert, Usb } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { ToggleRow } from '@/components/ui/ToggleRow'
import { usePrinter } from '@/lib/escpos/printerContext'
import type { PaperWidth } from '@/lib/escpos/receipt'
import {
  classifyConnectError,
  listNativeDevices,
  type NativeDevice,
  type TransportKind,
} from '@/lib/escpos/transport'

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
  // The native (in-app) transport has no OS chooser — WE list the bonded/attached printers and
  // the operator picks one (ADR 0043). null = picker closed.
  const [nativeDevices, setNativeDevices] = useState<NativeDevice[] | null>(null)
  const [nativeLoading, setNativeLoading] = useState(false)

  const transports: { kind: TransportKind; icon: typeof Usb; labelKey: string; hintKey: string }[] =
    [
      // In the Native Till app the bridge is the ONE real device path (the WebView has no
      // WebUSB/WebBluetooth) — put it first. Feature-detected: never present in a browser.
      ...(printer.support.native
        ? [
            {
              kind: 'native' as TransportKind,
              icon: Printer,
              labelKey: 'settings.printer.native',
              hintKey: 'settings.printer.nativeHint',
            },
          ]
        : []),
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
      {
        kind: 'rawbt',
        icon: Smartphone,
        labelKey: 'settings.printer.rawbt',
        hintKey: 'settings.printer.rawbtHint',
      },
    ]

  const connect = async (kind: TransportKind) => {
    setError(null)
    if (kind === 'native') {
      // Two-step in-app flow: list the paired/attached printers, the operator picks one below.
      setNativeLoading(true)
      try {
        setNativeDevices(await listNativeDevices())
      } catch (err) {
        // A denied Bluetooth permission must say "blocked", not "no printers found".
        setNativeDevices(null)
        setError(t(`settings.printer.error.${classifyConnectError(err, 'native')}`))
      } finally {
        setNativeLoading(false)
      }
      return
    }
    try {
      await printer.connect(kind, paper, printer.config?.drawerKick ?? false)
    } catch (err) {
      // Map the browser's DOMException to a specific reason. A cancelled chooser is not worth an
      // alarming banner — just clear the connecting state silently.
      const reason = classifyConnectError(err, kind)
      if (reason === 'cancelled') return
      setError(t(`settings.printer.error.${reason}`))
    }
  }

  const connectNative = async (device: NativeDevice) => {
    setError(null)
    try {
      await printer.connect('native', paper, printer.config?.drawerKick ?? false, {
        deviceId: device.id,
        label: device.name,
      })
      setNativeDevices(null)
    } catch (err) {
      const reason = classifyConnectError(err, 'native')
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
          <div className="flex basis-full flex-col gap-3 border-t border-line pt-4">
            {/* Live paper-width switch — the #1 "receipt prints as garbage" cause is an 80mm
                layout on a 58mm roll, so this must be changeable without disconnecting. */}
            <div className="flex items-center justify-between gap-3">
              <span className="text-sm font-semibold text-ink">
                {t('settings.printer.paperWidth')}
              </span>
              <div className="flex gap-2">
                {([58, 80] as const).map((w) => (
                  <button
                    key={w}
                    type="button"
                    onClick={() => {
                      setPaper(w)
                      printer.setPaper(w)
                    }}
                    className={`rounded-lg border px-3 py-1.5 text-sm font-semibold transition-colors ${
                      (printer.config?.paper ?? paper) === w
                        ? 'border-brand-500 bg-brand-50 text-brand-700'
                        : 'border-line text-ink-2 hover:bg-hover'
                    }`}
                  >
                    {w}mm
                  </button>
                ))}
              </div>
            </div>
            <ToggleRow
              label={t('settings.printer.autoPrint')}
              hint={t('settings.printer.autoPrintHint')}
              checked={printer.config?.autoPrint ?? false}
              onToggle={() => printer.setAutoPrint(!(printer.config?.autoPrint ?? false))}
            />
            <ToggleRow
              label={t('settings.printer.drawerKick')}
              hint={t('settings.printer.drawerKickHint')}
              checked={printer.config?.drawerKick ?? false}
              onToggle={() => printer.setDrawerKick(!(printer.config?.drawerKick ?? false))}
            />
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
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
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
                  disabled={
                    !supported ||
                    printer.connectingLabel === kind ||
                    (kind === 'native' && nativeLoading)
                  }
                  onClick={() => connect(kind)}
                >
                  {printer.connectingLabel === kind || (kind === 'native' && nativeLoading)
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

      {!printer.connected && nativeDevices !== null ? (
        <Card className="flex flex-col gap-3 p-5">
          <div className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
            {t('settings.printer.nativePickTitle')}
          </div>
          {nativeDevices.length === 0 ? (
            <p className="text-sm text-ink-3">{t('settings.printer.nativeNoDevices')}</p>
          ) : (
            <div className="flex flex-col gap-2">
              {nativeDevices.map((device) => (
                <button
                  key={device.id}
                  type="button"
                  disabled={printer.connectingLabel === 'native'}
                  onClick={() => connectNative(device)}
                  className="flex items-center justify-between gap-3 rounded-lg border border-line px-4 py-2.5 text-left transition-colors hover:bg-hover disabled:opacity-60"
                >
                  <span className="flex items-center gap-2.5">
                    {device.kind === 'usb' ? (
                      <Usb className="size-4 text-brand-700" />
                    ) : (
                      <Bluetooth className="size-4 text-brand-700" />
                    )}
                    <span className="text-sm font-semibold text-ink">{device.name}</span>
                  </span>
                  <span className="text-xs text-ink-3">
                    {t(`settings.printer.nativeKind.${device.kind}`)} · {device.id}
                  </span>
                </button>
              ))}
            </div>
          )}
          <div className="flex justify-end">
            <Button variant="outline" onClick={() => connect('native')} disabled={nativeLoading}>
              {t('settings.printer.nativeRescan')}
            </Button>
          </div>
        </Card>
      ) : null}

      {error ? (
        <Card className="flex items-start gap-2 p-4 text-sm text-loss">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <span>{error}</span>
        </Card>
      ) : null}

      <p className="text-xs text-ink-3">{t('settings.printer.compatNote')}</p>
      <p className="text-xs text-ink-3">{t('settings.printer.buyNote')}</p>
    </div>
  )
}
