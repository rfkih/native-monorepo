import { describe, it, expect, vi, afterEach } from 'vitest'
import { classifyConnectError, rawbtIntentUrl, transportSupport } from '../transport'

function domError(name: string, message = ''): Error {
  const err = new Error(message)
  err.name = name
  return err
}

describe('rawbtIntentUrl', () => {
  it('wraps the bytes as a rawbt base64 intent pinned to the RawBT package', () => {
    const url = rawbtIntentUrl(new Uint8Array([0x1b, 0x40, 0x48, 0x69, 0x0a]))
    expect(url.startsWith('intent:base64,')).toBe(true)
    expect(url).toContain('#Intent;scheme=rawbt;package=ru.a402d.rawbtprinter;')
    expect(url).toContain('S.browser_fallback_url=')
    expect(url.endsWith(';end;')).toBe(true)
  })

  it('round-trips the exact ESC/POS bytes through the base64 payload', () => {
    // Every byte value 0..255 — the stream is binary (drawer-kick pulses, GS commands), not text.
    const bytes = new Uint8Array(256).map((_, i) => i)
    const url = rawbtIntentUrl(bytes)
    const b64 = decodeURI(url.slice('intent:base64,'.length, url.indexOf('#Intent;')))
    const decoded = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
    expect(Array.from(decoded)).toEqual(Array.from(bytes))
  })

  it('handles payloads larger than one String.fromCharCode chunk', () => {
    const bytes = new Uint8Array(100_000).fill(0x41)
    const url = rawbtIntentUrl(bytes)
    const b64 = decodeURI(url.slice('intent:base64,'.length, url.indexOf('#Intent;')))
    expect(atob(b64).length).toBe(100_000)
  })

  it('percent-encodes the Play-Store fallback URL for the RawBT package', () => {
    const url = rawbtIntentUrl(new Uint8Array([0x0a]))
    const fallback = /S\.browser_fallback_url=([^;]+);/.exec(url)?.[1]
    expect(fallback).toBeDefined()
    // Encoded (no raw :/?=& that would break intent-extra parsing) and decodes to the store page.
    expect(fallback).not.toMatch(/[:/?&]/)
    expect(decodeURIComponent(fallback!)).toBe(
      'https://play.google.com/store/apps/details?id=ru.a402d.rawbtprinter',
    )
  })
})

describe('transportSupport', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('offers rawbt on Android only', () => {
    vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Linux; Android 14; SM-X200) Chrome/126' })
    expect(transportSupport().rawbt).toBe(true)
    vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64) Chrome/126' })
    expect(transportSupport().rawbt).toBe(false)
  })
})

describe('classifyConnectError', () => {
  it('keeps the chooser-dismissed and permission cases stable', () => {
    expect(classifyConnectError(domError('NotFoundError'))).toBe('cancelled')
    expect(classifyConnectError(domError('SecurityError'))).toBe('blocked')
    expect(classifyConnectError(domError('NotAllowedError'), 'ble')).toBe('blocked')
  })

  it('maps a BLE NetworkError to bleUnreachable (Classic-only / held-by-another-app)', () => {
    expect(classifyConnectError(domError('NetworkError', 'Connection attempt failed.'), 'ble')).toBe(
      'bleUnreachable',
    )
  })

  it('keeps NetworkError/InvalidStateError as inUse for USB and serial', () => {
    expect(classifyConnectError(domError('NetworkError', 'Unable to claim interface.'), 'usb')).toBe(
      'inUse',
    )
    expect(classifyConnectError(domError('InvalidStateError'), 'serial')).toBe('inUse')
    // No kind (legacy callers) — unchanged behavior.
    expect(classifyConnectError(domError('NetworkError'))).toBe('inUse')
  })

  it('still detects the not-a-printer picks', () => {
    expect(classifyConnectError(new Error('no bulk OUT endpoint on device'), 'usb')).toBe(
      'noEndpoint',
    )
    expect(classifyConnectError(new Error('no writable characteristic'), 'ble')).toBe('noEndpoint')
  })
})
