import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  classifyConnectError,
  createRawbtTransport,
  rawbtIntentUrl,
  sendViaRawbtWs,
  transportSupport,
} from '../transport'

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

/**
 * Minimal structural stand-in for the browser WebSocket: fires onopen/onerror asynchronously
 * (like the real one — never inside the constructor) according to the scripted behavior.
 */
class FakeWebSocket {
  static behavior: 'open' | 'error' | 'hang' | 'lateOpen' | 'sendThrows' = 'open'
  static last: FakeWebSocket | null = null
  binaryType = ''
  sent: unknown[] = []
  closed: number | null = null
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor() {
    FakeWebSocket.last = this
    if (FakeWebSocket.behavior === 'lateOpen') {
      setTimeout(() => this.onopen?.(), 40) // arrives AFTER the caller's timeout in these tests
      return
    }
    queueMicrotask(() => {
      if (FakeWebSocket.behavior === 'open' || FakeWebSocket.behavior === 'sendThrows')
        this.onopen?.()
      else if (FakeWebSocket.behavior === 'error') this.onerror?.()
      // 'hang': neither fires — the caller's timeout must resolve it
    })
  }
  send(data: unknown) {
    if (FakeWebSocket.behavior === 'sendThrows') throw new Error('socket gone')
    this.sent.push(data)
  }
  close(code: number) {
    this.closed = code
  }
}

describe('sendViaRawbtWs / rawbt transport write', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the bytes as one binary message and closes normally', async () => {
    FakeWebSocket.behavior = 'open'
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const bytes = new Uint8Array([0x1b, 0x40])
    await expect(sendViaRawbtWs(bytes)).resolves.toBe(true)
    expect(FakeWebSocket.last?.binaryType).toBe('arraybuffer')
    expect(FakeWebSocket.last?.sent).toHaveLength(1)
    expect(Array.from(new Uint8Array(FakeWebSocket.last!.sent[0] as ArrayBuffer))).toEqual([
      0x1b, 0x40,
    ])
    expect(FakeWebSocket.last?.closed).toBe(1000)
  })

  it('resolves false when the server app is not there (refused / hung / no API)', async () => {
    FakeWebSocket.behavior = 'error'
    vi.stubGlobal('WebSocket', FakeWebSocket)
    await expect(sendViaRawbtWs(new Uint8Array([1]))).resolves.toBe(false)

    FakeWebSocket.behavior = 'hang'
    await expect(sendViaRawbtWs(new Uint8Array([1]), 20)).resolves.toBe(false)

    // The guard branch, explicitly — NOT unstubbed (Node ships a real global WebSocket, which
    // would open a genuine socket to 127.0.0.1:40213 here).
    vi.stubGlobal('WebSocket', undefined)
    await expect(sendViaRawbtWs(new Uint8Array([1]), 20)).resolves.toBe(false)
  })

  it('a connect that completes only after the timeout must not send (double-print guard)', async () => {
    FakeWebSocket.behavior = 'lateOpen'
    vi.stubGlobal('WebSocket', FakeWebSocket)
    await expect(sendViaRawbtWs(new Uint8Array([1]), 10)).resolves.toBe(false)
    await new Promise((r) => setTimeout(r, 60)) // let the late onopen fire
    expect(FakeWebSocket.last?.sent).toEqual([])
  })

  it('a throwing send resolves false so the intent fallback can take over', async () => {
    FakeWebSocket.behavior = 'sendThrows'
    vi.stubGlobal('WebSocket', FakeWebSocket)
    await expect(sendViaRawbtWs(new Uint8Array([1]))).resolves.toBe(false)
  })

  it('write() prefers the silent WebSocket and only falls back to the intent URL without it', async () => {
    const loc = { href: '' }
    vi.stubGlobal('window', { location: loc })

    FakeWebSocket.behavior = 'open'
    vi.stubGlobal('WebSocket', FakeWebSocket)
    await createRawbtTransport().write(new Uint8Array([0x0a]))
    expect(loc.href).toBe('') // no navigation — no RawBT popup

    FakeWebSocket.behavior = 'error'
    await createRawbtTransport().write(new Uint8Array([0x0a]))
    expect(loc.href.startsWith('intent:base64,')).toBe(true)
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
