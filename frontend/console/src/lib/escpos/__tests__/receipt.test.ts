import { describe, it, expect } from 'vitest'
import { EscposEncoder, toAscii } from '../encoder'
import { renderReceipt, columnsFor, type EscposReceiptData } from '../receipt'

const ESC = 0x1b
const GS = 0x1d

function bytes(u8: Uint8Array): number[] {
  return Array.from(u8)
}

/** The ASCII text a printer would render, dropping control/format bytes, for content assertions. */
function printableText(u8: Uint8Array): string {
  let out = ''
  for (let i = 0; i < u8.length; i++) {
    const b = u8[i]
    if (b === ESC) {
      // ESC commands: @ (1), a/E/d (1 arg), else skip the command byte only.
      const cmd = u8[i + 1]
      if (cmd === 0x40) i += 1
      else if (cmd === 0x61 || cmd === 0x45 || cmd === 0x64 || cmd === 0x70) i += cmd === 0x70 ? 4 : 2
      else i += 1
    } else if (b === GS) {
      const cmd = u8[i + 1]
      if (cmd === 0x21) i += 2
      else if (cmd === 0x56) i += 3
      else i += 1
    } else if (b === 0x0a) {
      out += '\n'
    } else {
      out += String.fromCharCode(b)
    }
  }
  return out
}

describe('EscposEncoder', () => {
  it('starts a receipt with ESC @ init', () => {
    const out = bytes(new EscposEncoder().init().build())
    expect(out.slice(0, 2)).toEqual([ESC, 0x40])
  })

  it('emits the documented cut and drawer-kick sequences', () => {
    expect(bytes(new EscposEncoder().cut().build())).toEqual([GS, 0x56, 0x42, 0x00])
    expect(bytes(new EscposEncoder().drawerKick().build())).toEqual([ESC, 0x70, 0x00, 0x32, 0xfa])
  })

  it('never emits a byte above 0x7f (ASCII-safe wire)', () => {
    const out = new EscposEncoder().init().line('Nasi Goreng — Rp 25.000 × 2').build()
    for (const b of out) expect(b).toBeLessThanOrEqual(0x7f)
  })
})

describe('toAscii', () => {
  it('transliterates the typographic characters our formatters emit', () => {
    // em-dash→--, en-dash→-, curly quotes→straight, ellipsis→..., ×→x, ·→.
    expect(toAscii('—–’”…×·')).toBe(`---'"...x.`)
  })
  it('replaces anything else with ?', () => {
    expect(toAscii('café 日本')).toBe('caf? ??')
  })
})

const DATA: EscposReceiptData = {
  businessName: 'Warung Makmur',
  title: 'Receipt',
  reference: 'AB12CD34',
  tagline: 'Dine-in',
  dateTime: '2026-08-06 14:30',
  metaRows: [{ label: 'Table', valueLabel: '7' }],
  lineItems: [
    {
      qty: 2,
      name: 'Nasi Goreng Special Extra Long Name That Wraps',
      priceLabel: 'Rp 50.000',
      modifiers: [{ label: 'Extra egg', deltaLabel: '+Rp 2.000' }],
    },
    { qty: 1, name: 'Es Teh', priceLabel: 'Rp 8.000', modifiers: [] },
  ],
  totalRows: [
    { label: 'Subtotal', valueLabel: 'Rp 58.000' },
    { label: 'Tax', valueLabel: 'Rp 6.380' },
  ],
  grandTotalLabel: 'Rp 64.380',
  grandTotalCaption: 'Total',
  paymentRows: [{ label: 'Tender', valueLabel: 'Cash' }],
  footerNote: 'Thank you for your visit!',
}

describe('renderReceipt', () => {
  it('has 32 columns on 58mm and 48 on 80mm', () => {
    expect(columnsFor(58)).toBe(32)
    expect(columnsFor(80)).toBe(48)
  })

  it('renders every content string and keeps lines within the paper width', () => {
    const out = renderReceipt(DATA, 58, { drawerKick: false })
    const text = printableText(out)
    expect(text).toContain('WARUNG MAKMUR')
    expect(text).toContain('Nasi Goreng')
    expect(text).toContain('Rp 50.000')
    expect(text).toContain('Es Teh')
    expect(text).toContain('Extra egg')
    expect(text).toContain('Rp 64.380')
    expect(text).toContain('Thank you for your visit')
    // No printed line exceeds 32 columns on 58mm paper.
    for (const l of text.split('\n')) expect(l.length).toBeLessThanOrEqual(32)
  })

  it('right-aligns amounts flush to the paper edge', () => {
    const out = renderReceipt(DATA, 58, { drawerKick: false })
    const line = printableText(out)
      .split('\n')
      .find((l) => l.includes('Rp 8.000'))
    expect(line).toBeDefined()
    expect(line!.endsWith('Rp 8.000')).toBe(true)
    expect(line!.length).toBe(32)
  })

  it('emits the drawer kick before content only when asked', () => {
    const withKick = bytes(renderReceipt(DATA, 80, { drawerKick: true }))
    // ESC @ then ESC p ... — the kick is right after init.
    expect(withKick.slice(0, 2)).toEqual([ESC, 0x40])
    expect(withKick.slice(2, 4)).toEqual([ESC, 0x70])
    const noKick = bytes(renderReceipt(DATA, 80, { drawerKick: false }))
    expect(noKick.slice(2, 4)).not.toEqual([ESC, 0x70])
  })

  it('ends with feed + cut', () => {
    const out = bytes(renderReceipt(DATA, 80, { drawerKick: false }))
    expect(out.slice(-4)).toEqual([GS, 0x56, 0x42, 0x00])
  })

  it('prints the provisional banner when present', () => {
    const text = printableText(
      renderReceipt({ ...DATA, provisionalNote: 'Provisional' }, 58, { drawerKick: false }),
    )
    expect(text).toContain('PROVISIONAL')
  })

  it('prints the reversed-sale banner when present, and omits it when unset', () => {
    const withNote = renderReceipt(
      { ...DATA, reversedNote: 'Transaksi dibatalkan' },
      58,
      { drawerKick: false },
    )
    const text = printableText(withNote)
    expect(text).toContain('TRANSAKSI DIBATALKAN')
    for (const l of text.split('\n')) expect(l.length).toBeLessThanOrEqual(32)

    const withoutNote = printableText(renderReceipt(DATA, 58, { drawerKick: false }))
    expect(withoutNote).not.toContain('TRANSAKSI DIBATALKAN')
  })

  it('wraps a long partial-refund banner within 32 columns on 58mm paper', () => {
    // The partial-refund variant interpolates a formatted amount (Intl output carries an
    // em-dash and NBSP) and is the only banner long enough to force a wrap.
    const text = printableText(
      renderReceipt(
        { ...DATA, reversedNote: 'Dikembalikan sebagian — Rp 125.000' },
        58,
        { drawerKick: false },
      ),
    )
    expect(text).toContain('DIKEMBALIKAN SEBAGIAN')
    expect(text).toContain('125.000')
    for (const l of text.split('\n')) expect(l.length).toBeLessThanOrEqual(32)
  })
})
