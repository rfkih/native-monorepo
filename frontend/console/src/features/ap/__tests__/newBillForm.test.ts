import { describe, expect, it } from 'vitest'
import {
  applyBasisPoints,
  checklist,
  dueDateOf,
  effectiveTerms,
  isDuplicateInvoice,
  parseLine,
  reconcile,
  totals,
  vendorInitials,
  type DraftLine,
  type IngredientRef,
} from '../lib/newBillForm'

const ayam: IngredientRef = { id: 'ayam', name: 'Ayam broiler', unit: 'g', displayUnit: 'kg' }
const telur: IngredientRef = { id: 'telur', name: 'Telur', unit: 'pcs', displayUnit: null }

const expense = (over: Partial<Extract<DraftLine, { kind: 'expense' }>> = {}): DraftLine => ({
  key: 'l1',
  kind: 'expense',
  description: 'Sewa freezer',
  qty: '1',
  price: '600000',
  ...over,
})
const inventory = (over: Partial<Extract<DraftLine, { kind: 'inventory' }>> = {}): DraftLine => ({
  key: 'l2',
  kind: 'inventory',
  ingredient: ayam,
  description: '',
  qty: '24',
  price: '34500',
  ...over,
})

describe('parseLine — expense lines', () => {
  it('a whole quantity travels as quantity × unit price', () => {
    const r = parseLine(expense({ qty: '3', price: '15.000' }), 'IDR')
    expect(r).toEqual({
      ok: true,
      totalMinor: 45_000,
      unit: null,
      body: { description: 'Sewa freezer', quantity: 3, unitPriceMinor: 15_000, inventory: false },
    })
  })

  it('a fractional quantity folds into one unit at the rounded total', () => {
    const r = parseLine(expense({ qty: '1,5', price: '10000' }), 'IDR')
    expect(r.ok && r.body).toEqual({
      description: 'Sewa freezer',
      quantity: 1,
      unitPriceMinor: 15_000,
      inventory: false,
    })
  })

  it('names the first thing wrong: no name, then no amount', () => {
    expect(parseLine(expense({ description: '  ' }), 'IDR')).toEqual({ ok: false, issue: 'name' })
    expect(parseLine(expense({ price: '' }), 'IDR')).toEqual({ ok: false, issue: 'amount' })
    expect(parseLine(expense({ qty: '0' }), 'IDR')).toEqual({ ok: false, issue: 'amount' })
  })
})

describe('parseLine — inventory (Persediaan) lines', () => {
  it('travels the ADR 0072 way: one unit at the total, the real quantity in base units', () => {
    const r = parseLine(inventory({ qty: '2,5', price: '34500' }), 'IDR')
    expect(r).toEqual({
      ok: true,
      totalMinor: 86_250,
      unit: 'kg',
      body: {
        description: 'Ayam broiler',
        quantity: 1,
        unitPriceMinor: 86_250,
        inventory: true,
        ingredientId: 'ayam',
        ingredientName: 'Ayam broiler',
        ingredientQtyBase: 2_500,
      },
    })
  })

  it('keeps the receipt wording when given, and refuses a fraction of a piece', () => {
    const named = parseLine(inventory({ description: 'AYAM BRLR 24KG' }), 'IDR')
    expect(named.ok && named.body.description).toBe('AYAM BRLR 24KG')
    expect(parseLine(inventory({ ingredient: telur, qty: '1.5', price: '2000' }), 'IDR')).toEqual({
      ok: false,
      issue: 'amount',
    })
    expect(parseLine(inventory({ ingredient: null }), 'IDR')).toEqual({
      ok: false,
      issue: 'ingredient',
    })
  })
})

describe('totals — subtotal, discount, DPP, PPN, total', () => {
  it('taxes the net after the discount at the chosen rate', () => {
    expect(totals([600_000, 400_000], 100_000, 1200)).toEqual({
      subtotalMinor: 1_000_000,
      discountMinor: 100_000,
      dppMinor: 900_000,
      taxMinor: 108_000,
      totalMinor: 1_008_000,
    })
  })

  it('rounds the tax HALF_EVEN like the server — an exact half goes to the even unit', () => {
    // 15,150 × 11 % = 1,666.5 → 1,666 (even), not 1,667; 15,350 × 11 % = 1,688.5 → 1,688.
    expect(applyBasisPoints(15_150, 1100)).toBe(1_666)
    expect(applyBasisPoints(15_350, 1100)).toBe(1_688)
    expect(applyBasisPoints(15_250, 1100)).toBe(1_678) // 1,677.5 → 1,678 (even)
    expect(applyBasisPoints(1_001, 1100)).toBe(110)
    expect(applyBasisPoints(900_000, 1200)).toBe(108_000)
    expect(totals([15_150], 0, 1100).totalMinor).toBe(16_816)
  })

  it('clamps the discount to the subtotal and rounds the tax once', () => {
    expect(totals([1_001], 5_000, 1100)).toEqual({
      subtotalMinor: 1_001,
      discountMinor: 1_001,
      dppMinor: 0,
      taxMinor: 0,
      totalMinor: 0,
    })
    expect(totals([1_001], 0, 1100).taxMinor).toBe(110)
  })
})

describe('reconcile — the printed total against the computed one', () => {
  it('empty / match / higher / lower', () => {
    expect(reconcile(1_008_000, null)).toEqual({ state: 'empty' })
    expect(reconcile(1_008_000, 0)).toEqual({ state: 'empty' })
    expect(reconcile(1_008_000, 1_008_000)).toEqual({ state: 'match' })
    expect(reconcile(1_008_000, 1_010_000)).toEqual({ state: 'higher', diffMinor: 2_000 })
    expect(reconcile(1_008_000, 1_000_000)).toEqual({ state: 'lower', diffMinor: 8_000 })
  })
})

describe('terms and due date', () => {
  it("the owner's pick wins, then the vendor, then 30", () => {
    expect(effectiveTerms(14, null)).toBe(14)
    expect(effectiveTerms(14, 0)).toBe(0)
    expect(effectiveTerms(null, null)).toBe(30)
    expect(effectiveTerms(undefined, 7)).toBe(7)
  })

  it('adds whole days across a month boundary; garbage is null', () => {
    expect(dueDateOf('2026-09-11', 30)).toBe('2026-10-11')
    expect(dueDateOf('2026-09-11', 0)).toBe('2026-09-11')
    expect(dueDateOf('nope', 30)).toBeNull()
  })
})

describe('isDuplicateInvoice — the same vendor, a live bill, case-insensitive', () => {
  const bills = [
    { vendorId: 'v1', status: 'POSTED', vendorInvoiceNumber: 'INV/2026/08/2214' },
    { vendorId: 'v1', status: 'VOID', vendorInvoiceNumber: 'INV/2026/08/2190' },
    { vendorId: 'v2', status: 'DRAFT', vendorInvoiceNumber: 'ABN-9921' },
  ]
  it('flags a live match, ignores void bills, other vendors and blanks', () => {
    expect(isDuplicateInvoice(bills, 'v1', ' inv/2026/08/2214 ')).toBe(true)
    expect(isDuplicateInvoice(bills, 'v1', 'INV/2026/08/2190')).toBe(false)
    expect(isDuplicateInvoice(bills, 'v1', 'ABN-9921')).toBe(false)
    expect(isDuplicateInvoice(bills, null, 'INV/2026/08/2214')).toBe(false)
    expect(isDuplicateInvoice(bills, 'v1', '')).toBe(false)
  })
})

describe('checklist — every reason the bill cannot be saved yet, in order', () => {
  it('lists what is missing, counting the lines', () => {
    const issues = checklist({
      vendorId: null,
      invoiceNumber: '',
      duplicate: false,
      lines: [
        { ok: false, issue: 'name' },
        { ok: false, issue: 'ingredient' },
        { ok: false, issue: 'amount' },
        {
          ok: true,
          totalMinor: 1,
          unit: null,
          body: { description: 'x', quantity: 1, unitPriceMinor: 1 },
        },
      ],
      reconciliation: { state: 'higher', diffMinor: 2_000 },
      attached: false,
    })
    expect(issues).toEqual([
      { key: 'vendor' },
      { key: 'invoiceNumber' },
      { key: 'unnamed', count: 2 },
      { key: 'unpriced', count: 1 },
      { key: 'mismatch', diffMinor: 2_000 },
      { key: 'attachment' },
    ])
  })

  it('is empty when everything is in place', () => {
    expect(
      checklist({
        vendorId: 'v1',
        invoiceNumber: 'INV/1',
        duplicate: false,
        lines: [
          {
            ok: true,
            totalMinor: 1,
            unit: null,
            body: { description: 'x', quantity: 1, unitPriceMinor: 1 },
          },
        ],
        reconciliation: { state: 'match' },
        attached: true,
      }),
    ).toEqual([])
  })

  it('a duplicate and an unfilled printed total are their own items', () => {
    const issues = checklist({
      vendorId: 'v1',
      invoiceNumber: 'INV/1',
      duplicate: true,
      lines: [],
      reconciliation: { state: 'empty' },
      attached: true,
    })
    expect(issues.map((i) => i.key)).toEqual(['duplicate', 'noLines', 'printed'])
  })
})

describe('vendorInitials', () => {
  it('drops the legal-form prefix and takes two initials', () => {
    expect(vendorInitials('CV Sumber Pangan Jaya')).toBe('SP')
    expect(vendorInitials('PT. Aneka Boga')).toBe('AB')
    expect(vendorInitials('Tirta')).toBe('T')
    expect(vendorInitials('')).toBe('')
  })
})
