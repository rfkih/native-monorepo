import { describe, expect, it } from 'vitest'
import { previewReceive, previewSet } from './qtyDraft'

const kg = { unit: 'g', displayUnit: 'kg' }
const pcs = { unit: 'pcs', displayUnit: null }

describe('previewReceive', () => {
  it('adds in base units from a shown-unit draft, either separator', () => {
    expect(previewReceive('4', 'add', 8400, kg)).toEqual({
      ok: true,
      deltaBase: 4000,
      afterBase: 12400,
      clipped: false,
    })
    expect(previewReceive('1,2', 'remove', 8400, kg)).toEqual({
      ok: true,
      deltaBase: -1200,
      afterBase: 7200,
      clipped: false,
    })
    expect(previewReceive('1.2', 'remove', 8400, kg)).toMatchObject({ deltaBase: -1200 })
  })

  it('refuses an empty, zero or fractional-on-whole-unit draft', () => {
    expect(previewReceive('', 'add', 10, pcs)).toEqual({ ok: false })
    expect(previewReceive('0', 'add', 10, pcs)).toEqual({ ok: false })
    expect(previewReceive('0,5', 'add', 10, pcs)).toEqual({ ok: false })
    expect(previewReceive('abc', 'add', 10, pcs)).toEqual({ ok: false })
  })

  it('floors a correction at zero and says so, like the server', () => {
    expect(previewReceive('12', 'remove', 8000, kg)).toEqual({
      ok: true,
      deltaBase: -12000,
      afterBase: 0,
      clipped: true,
    })
  })
})

describe('previewSet', () => {
  it('reads the absolute figure and the difference from the system', () => {
    expect(previewSet('7,9', 8400, kg)).toEqual({ ok: true, afterBase: 7900, diffBase: -500 })
    expect(previewSet('240', 200, pcs)).toEqual({ ok: true, afterBase: 240, diffBase: 40 })
  })

  it('accepts zero — "we are out" is a real count', () => {
    expect(previewSet('0', 6, pcs)).toEqual({ ok: true, afterBase: 0, diffBase: -6 })
  })

  it('refuses what parseShownQtyInput refuses', () => {
    expect(previewSet('', 6, pcs)).toEqual({ ok: false })
    expect(previewSet('2,5', 6, pcs)).toEqual({ ok: false })
  })
})
