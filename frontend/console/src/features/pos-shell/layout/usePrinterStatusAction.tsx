/**
 * usePrinterStatusAction — the printer pill for PosStatusBar's pinned-actions slot (P1 printing-
 * flow hardening). ThermalReceipt's recovery banner (features/pos/ThermalReceipt.tsx) covers a
 * print that just FAILED, mid-sale; this covers the complementary gap — a cashier had no way to
 * know the printer was even connected BEFORE ringing a sale. Always visible in the status bar, not
 * buried in /settings/printer.
 *
 * Kept OUT of PosStatusBar.tsx itself: that component is documented as stateless presentation ("no
 * hooks beyond i18n, no fetching") so every pos-shell vertical (restaurant, carwash, barbershop)
 * can reuse it unchanged. This hook does the (small) stateful work and hands back a plain
 * StatusBarAction the caller spreads into `pinned` — the same shape Pos.tsx's tables/parked actions
 * already use.
 */
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Printer } from 'lucide-react'
import { usePrinter } from '@/lib/escpos/printerContext'
import type { StatusBarAction } from './PosStatusBar'

export function usePrinterStatusAction(): StatusBarAction {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const printer = usePrinter()

  const neverConfigured = printer.config == null
  const reconnecting = printer.connectingLabel != null

  const label = neverConfigured
    ? t('posShell.printerSetUp')
    : printer.connected
      ? t('posShell.printerConnected')
      : t('posShell.printerDisconnected')

  return {
    key: 'printer',
    icon: <Printer className="size-4" aria-hidden="true" />,
    label,
    onClick: () => {
      // Connected (nothing to fix) or never configured on this device (nothing saved to
      // reconnect to) — both land on Settings, the one place that can change either. A KNOWN but
      // currently-disconnected printer gets the fast path: reconnect right here, no navigation —
      // same "Reconnect" action ThermalReceipt's banner uses (USB/serial/RawBT/native reattach
      // silently; BLE legitimately reopens its chooser because this click IS the user gesture).
      if (printer.connected || neverConfigured) {
        navigate('/settings/printer')
        return
      }
      void printer.reconnect()
    },
    disabled: reconnecting,
    disabledTitle: t('posShell.printerReconnecting'),
    // The warning badge is the at-a-glance "this needs attention" signal — absent when connected
    // or never configured (the latter isn't a FAILURE, just an unconfigured till).
    badge: !neverConfigured && !printer.connected ? { count: 1, tone: 'warning' } : null,
    testId: 'pos-printer-status',
  }
}
