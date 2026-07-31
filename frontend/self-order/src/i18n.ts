/**
 * i18n.ts — a typed `t()` over two literal objects. A full i18next stack is overkill for ~15
 * strings in a single-purpose kiosk app (task brief) — but the rule still applies: no hardcoded
 * strings inline in JSX, every user-facing string routes through this dictionary, id + en shipped
 * together. `Lang` has no per-user toggle (this is a shared table/kiosk device, not a personal
 * login) — id is the default per ADR 0029; App.tsx picks the language once at startup.
 */

export type Lang = 'id' | 'en'

const dict = {
  id: {
    loading: 'Memuat menu…',
    tokenMissing: 'Tautan tidak valid. Silakan pindai ulang kode QR di meja Anda.',
    tokenInvalid: 'Kode QR ini sudah tidak berlaku. Silakan minta staf mencetak ulang.',
    networkError: 'Tidak dapat terhubung. Periksa koneksi Anda dan coba lagi.',
    retry: 'Coba lagi',
    emptyMenu: 'Menu belum tersedia saat ini.',
    yourOrder: 'Pesanan Anda',
    add: 'Tambah',
    soldOut: 'Habis',
    cartCount: '{{n}} item',
    cartEmptyHint: 'Ketuk "Tambah" pada menu untuk mulai memesan',
    sendOrder: 'Kirim pesanan',
    sending: 'Mengirim…',
    queueFull: 'Dapur sedang sangat sibuk — coba kirim pesanan lagi sebentar lagi.',
    submitError: 'Pesanan gagal dikirim. Silakan coba lagi.',
    confirmedTitle: 'Pesanan terkirim — silakan bayar di kasir',
    confirmedTable: 'Meja {{label}}',
    orderAgain: 'Pesan lagi',
    otherCategory: 'Lainnya',
  },
  en: {
    loading: 'Loading menu…',
    tokenMissing: 'Invalid link. Please re-scan the QR code at your table.',
    tokenInvalid: 'This QR code is no longer valid. Please ask staff to reprint it.',
    networkError: 'Could not connect. Check your connection and try again.',
    retry: 'Retry',
    emptyMenu: 'The menu is not available right now.',
    yourOrder: 'Your order',
    add: 'Add',
    soldOut: 'Sold out',
    cartCount: '{{n}} item(s)',
    cartEmptyHint: 'Tap "Add" on a menu item to start your order',
    sendOrder: 'Send order',
    sending: 'Sending…',
    queueFull: 'The kitchen is very busy — please try sending your order again shortly.',
    submitError: 'Could not send your order. Please try again.',
    confirmedTitle: 'Order sent — please pay at the counter',
    confirmedTable: 'Table {{label}}',
    orderAgain: 'Order again',
    otherCategory: 'Other',
  },
} as const

type Key = keyof typeof dict.id

export function createT(lang: Lang) {
  return function t(key: Key, params?: Record<string, string | number>): string {
    let str: string = dict[lang][key]
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        str = str.replaceAll(`{{${k}}}`, String(v))
      }
    }
    return str
  }
}
