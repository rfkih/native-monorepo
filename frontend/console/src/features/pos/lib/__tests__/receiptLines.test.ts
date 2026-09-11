import { describe, expect, it } from 'vitest'
import { formatMoney } from '@/lib/money'
import { receiptLineItems } from '../receiptLines'

// The owner's report (2026-09-11): with an add-on on the line, the receipt's product row showed
// the FINAL line total (product + add-ons) while the add-on row beneath it ALSO showed its price —
// so the product looked more expensive than it is, and nothing on the paper added up. A receipt's
// item block must be checkable by the customer: product row = the product's own price × qty,
// each add-on row = that add-on × qty, and the subtotal is their sum.

const IDR = 'IDR'
const ID = 'id-ID'
// Expected labels come from the same formatter (Intl puts a non-breaking space after "Rp");
// the assertions are about the ARITHMETIC and the sign, not the whitespace glyph.
const rp = (minor: number) => formatMoney(minor, IDR, ID)

describe('receiptLineItems — the product row is the product, the add-on rows are the add-ons', () => {
  it('a line with a paid add-on splits into base × qty and add-on × qty', () => {
    const [item] = receiptLineItems(
      [
        {
          qty: 2,
          name: 'Nasi Goreng',
          unitPriceMinor: 25_000,
          modifiers: [{ nameSnapshot: 'Telur', priceDeltaMinor: 2_000 }],
        },
      ],
      IDR,
      ID,
    )
    // 2 × 25.000 — the product's own price, NOT the 54.000 line total.
    expect(item.priceLabel).toBe(rp(50_000))
    // 2 × 2.000 — the add-on extended over the quantity, so the two rows sum to the line total.
    expect(item.modifiers).toEqual([{ label: 'Telur', deltaLabel: `+${rp(4_000)}` }])
    expect(item.qty).toBe(2)
    expect(item.name).toBe('Nasi Goreng')
  })

  it('a free add-on shows its name and no amount', () => {
    const [item] = receiptLineItems(
      [{ qty: 1, name: 'Es Teh', unitPriceMinor: 8_000, modifiers: [{ nameSnapshot: 'Less ice', priceDeltaMinor: 0 }] }],
      IDR,
      ID,
    )
    expect(item.priceLabel).toBe(rp(8_000))
    expect(item.modifiers).toEqual([{ label: 'Less ice', deltaLabel: undefined }])
  })

  it('a negative add-on (a deduction) keeps its sign and is extended over the quantity too', () => {
    const [item] = receiptLineItems(
      [{ qty: 3, name: 'Kopi', unitPriceMinor: 15_000, modifiers: [{ nameSnapshot: 'No milk', priceDeltaMinor: -1_000 }] }],
      IDR,
      ID,
    )
    expect(item.priceLabel).toBe(rp(45_000))
    expect(item.modifiers[0].deltaLabel).toBe(rp(-3_000))
    expect(item.modifiers[0].deltaLabel).toMatch(/^-/)
  })

  it('a line without modifiers is just qty × unit price', () => {
    const [item] = receiptLineItems([{ qty: 4, name: 'Teh Tawar', unitPriceMinor: 3_000 }], IDR, ID)
    expect(item.priceLabel).toBe(rp(12_000))
    expect(item.modifiers).toEqual([])
  })

  it('the rows add up to the line total the server charged', () => {
    // Server: lineTotal = (unit + Σ deltas) × qty = (25.000 + 2.000 − 500) × 2 = 53.000
    const line = {
      qty: 2,
      name: 'Nasi Goreng',
      unitPriceMinor: 25_000,
      modifiers: [
        { nameSnapshot: 'Telur', priceDeltaMinor: 2_000 },
        { nameSnapshot: 'Tanpa kerupuk', priceDeltaMinor: -500 },
      ],
    }
    const [item] = receiptLineItems([line], IDR, ID)
    const base = line.unitPriceMinor * line.qty
    const extras = line.modifiers.reduce((s, m) => s + m.priceDeltaMinor * line.qty, 0)
    expect(base + extras).toBe(53_000)
    expect(item.priceLabel).toBe(rp(50_000))
    expect(item.modifiers.map((m) => m.deltaLabel)).toEqual([`+${rp(4_000)}`, rp(-1_000)])
  })
})

// The printed paper, end to end: the same line items through the ESC/POS renderer. The bytes the
// printer receives are what the customer holds, so this is the assertion that matters.
import { renderReceipt } from '@/lib/escpos/receipt'
import { toAscii } from '@/lib/escpos/encoder'

function paperText(u8: Uint8Array): string {
  let out = ''
  for (let i = 0; i < u8.length; i++) {
    const b = u8[i]
    if (b === 0x1b) {
      const cmd = u8[i + 1]
      if (cmd === 0x40) i += 1
      else if (cmd === 0x61 || cmd === 0x45 || cmd === 0x64 || cmd === 0x70) i += cmd === 0x70 ? 4 : 2
      else i += 1
    } else if (b === 0x1d) {
      const cmd = u8[i + 1]
      if (cmd === 0x21) i += 2
      else if (cmd === 0x56) i += 3
      else i += 1
    } else if (b === 0x0a) out += '\n'
    else out += String.fromCharCode(b)
  }
  return out
}

describe('receiptLineItems on paper (ESC/POS, 58 mm)', () => {
  it('prints the product at its own price and the add-on extended, in that order', () => {
    const lineItems = receiptLineItems(
      [{ qty: 2, name: 'Nasi Goreng', unitPriceMinor: 25_000, modifiers: [{ nameSnapshot: 'Telur', priceDeltaMinor: 2_000 }] }],
      IDR,
      ID,
    )
    const paper = paperText(
      renderReceipt(
        {
          businessName: 'Warung Kemang',
          title: 'Struk',
          reference: 'AB12CD34',
          dateTime: '2026-09-11 12:00',
          metaRows: [],
          lineItems: lineItems.map((it) => ({ ...it, modifiers: it.modifiers.map((m) => ({ ...m })) })),
          totalRows: [{ label: 'Subtotal', valueLabel: rp(54_000) }],
          grandTotalLabel: rp(54_000),
          grandTotalCaption: 'Total',
          paymentRows: [],
          footerNote: 'Terima kasih',
        },
        58,
        { drawerKick: false },
      ),
    )
    const product = paper.split('\n').find((l) => l.startsWith('2x Nasi Goreng'))
    const addOn = paper.split('\n').find((l) => l.includes('+ Telur'))
    expect(product).toBeDefined()
    expect(addOn).toBeDefined()
    // The product row carries 50.000 — not the 54.000 the line was charged.
    expect(product).toContain(toAscii(rp(50_000)))
    expect(product).not.toContain(toAscii(rp(54_000)))
    // The add-on row carries its amount for BOTH units.
    expect(addOn).toContain(toAscii(`+${rp(4_000)}`))
    // And the add-on prints beneath its product.
    expect(paper.indexOf('2x Nasi Goreng')).toBeLessThan(paper.indexOf('+ Telur'))
  })
})
