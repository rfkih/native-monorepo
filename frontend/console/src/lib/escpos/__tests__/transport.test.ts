import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  classifyConnectError,
  createRawbtTransport,
  isNativeShell,
  listNativeDevices,
  rawbtIntentUrl,
  requestNativePrinter,
  sendViaRawbtWs,
  silentReattach,
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

/** Structural stand-in for the app's Capacitor NativePrint plugin proxy (ADR 0043). */
function fakeNativeBridge() {
  const calls = {
    connect: [] as unknown[],
    write: [] as { base64: string }[],
    disconnect: 0,
  }
  return {
    calls,
    async listDevices() {
      return {
        devices: [
          { id: '66:22:AA:01:02:03', name: 'RPP58', kind: 'classic', bonded: true },
          { id: 'usb:0483:5740', name: 'POS-80', kind: 'usb', bonded: true },
        ],
      }
    },
    async connect(options: unknown) {
      calls.connect.push(options)
    },
    async write(options: { base64: string }) {
      calls.write.push(options)
    },
    async disconnect() {
      calls.disconnect += 1
    },
  }
}

describe('native app bridge (ADR 0043)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('feature-detects OFF in a plain browser and node — the console stays byte-for-byte inert', () => {
    // transportSupport also touches navigator — stub it so this doesn't lean on Node's global.
    vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64) Chrome/126' })
    // No window at all (this node test env) …
    expect(transportSupport().native).toBe(false)
    // … and a window without the bridge (every real browser).
    vi.stubGlobal('window', {})
    expect(transportSupport().native).toBe(false)
  })

  it('detects the bridge under both the direct and the Capacitor.Plugins shapes', () => {
    vi.stubGlobal('window', { NativePrint: fakeNativeBridge() })
    expect(transportSupport().native).toBe(true)
    vi.stubGlobal('window', { Capacitor: { Plugins: { NativePrint: fakeNativeBridge() } } })
    expect(transportSupport().native).toBe(true)
  })

  it('isNativeShell spots the WebView even when the injected bridge is MISSING (SW-served page)', () => {
    // Not in the shell: node, plain browsers.
    expect(isNativeShell()).toBe(false)
    vi.stubGlobal('window', {})
    expect(isNativeShell()).toBe(false)
    // The addJavascriptInterface object exists in EVERY page of the Capacitor WebView — even one
    // a service worker served, where window.Capacitor (network-layer injection) is absent.
    vi.stubGlobal('window', { androidBridge: {} })
    expect(isNativeShell()).toBe(true)
    // And the healthy in-app case / future non-Android shells.
    vi.stubGlobal('window', { Capacitor: { isNativePlatform: () => true } })
    expect(isNativeShell()).toBe(true)
  })

  it('lists the bonded/attached devices for the settings picker', async () => {
    vi.stubGlobal('window', { NativePrint: fakeNativeBridge() })
    const devices = await listNativeDevices()
    expect(devices.map((d) => d.kind)).toEqual(['classic', 'usb'])
    // And resolves empty (not throwing) without the bridge — callers need no guard.
    vi.stubGlobal('window', {})
    await expect(listNativeDevices()).resolves.toEqual([])
  })

  it('connects by device id and round-trips the exact ESC/POS bytes as base64', async () => {
    const bridge = fakeNativeBridge()
    vi.stubGlobal('window', { NativePrint: bridge })
    const transport = await requestNativePrinter('66:22:AA:01:02:03', 'RPP58')
    expect(bridge.calls.connect).toEqual([{ deviceId: '66:22:AA:01:02:03' }])
    expect(transport.kind).toBe('native')
    expect(transport.label).toBe('RPP58')

    // Every byte value 0..255 — the stream is binary (NUL, GS commands, drawer pulses), not text.
    const bytes = new Uint8Array(256).map((_, i) => i)
    await transport.write(bytes)
    const decoded = Uint8Array.from(atob(bridge.calls.write[0].base64), (c) => c.charCodeAt(0))
    expect(Array.from(decoded)).toEqual(Array.from(bytes))

    await transport.disconnect()
    expect(bridge.calls.disconnect).toBe(1)
  })

  it('handles a write longer than one String.fromCharCode chunk', async () => {
    const bridge = fakeNativeBridge()
    vi.stubGlobal('window', { NativePrint: bridge })
    const transport = await requestNativePrinter('66:22:AA:01:02:03')
    await transport.write(new Uint8Array(100_000).fill(0x41))
    expect(atob(bridge.calls.write[0].base64).length).toBe(100_000)
  })

  it('classifyConnectError trusts the plugin reject code directly on the native path', () => {
    for (const code of ['cancelled', 'blocked', 'inUse', 'noEndpoint', 'unknown'] as const) {
      const err = Object.assign(new Error('native failure'), { code })
      expect(classifyConnectError(err, 'native')).toBe(code)
    }
    // Codes outside the contract, missing codes, and non-Error rejections all degrade safely.
    expect(classifyConnectError(Object.assign(new Error('x'), { code: 'weird' }), 'native')).toBe(
      'unknown',
    )
    expect(classifyConnectError(new Error('no code'), 'native')).toBe('unknown')
    expect(classifyConnectError(null, 'native')).toBe('unknown')
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

/** A minimal WebUSB device good enough for openUsbDevice's interface/endpoint walk to succeed. */
function fakeUsbDevice() {
  return {
    manufacturerName: 'Acme',
    productName: 'Thermal 80',
    configuration: {
      interfaces: [
        {
          interfaceNumber: 0,
          alternates: [
            {
              interfaceClass: 7, // USB printer class — openUsbDevice prefers this
              endpoints: [{ direction: 'out', type: 'bulk', endpointNumber: 1 }],
            },
          ],
        },
      ],
    },
    async open() {},
    async selectConfiguration() {},
    async claimInterface() {},
    async close() {},
    async transferOut() {},
  }
}

/** A minimal WebSerial port good enough for openSerialPort to succeed. */
function fakeSerialPort() {
  return {
    async open() {},
    async close() {},
    writable: { getWriter: () => ({ write: async () => {}, releaseLock: () => {} }) },
  }
}

describe('silentReattach — the mount-time / Reconnect-button decision (P1 printing-flow hardening)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('USB: re-attaches when the browser still has the granted device', async () => {
    vi.stubGlobal('navigator', { usb: { getDevices: async () => [fakeUsbDevice()] } })
    const transport = await silentReattach({ transport: 'usb' })
    expect(transport?.kind).toBe('usb')
  })

  it('USB: resolves null when the browser grant is gone (never asks for one — no chooser on mount)', async () => {
    vi.stubGlobal('navigator', { usb: { getDevices: async () => [] } })
    await expect(silentReattach({ transport: 'usb' })).resolves.toBeNull()
  })

  it('serial: re-attaches when a previously-opened port is still there', async () => {
    vi.stubGlobal('navigator', { serial: { getPorts: async () => [fakeSerialPort()] } })
    const transport = await silentReattach({ transport: 'serial' })
    expect(transport?.kind).toBe('serial')
  })

  it('serial: resolves null with nothing to reattach to', async () => {
    vi.stubGlobal('navigator', { serial: { getPorts: async () => [] } })
    await expect(silentReattach({ transport: 'serial' })).resolves.toBeNull()
  })

  it('rawbt: re-attaches unconditionally on Android (no device grant to check)', async () => {
    vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Linux; Android 14) Chrome/126' })
    const transport = await silentReattach({ transport: 'rawbt' })
    expect(transport?.kind).toBe('rawbt')
  })

  it('rawbt: resolves null off Android (feature-detected off — never silently "reconnects" to nothing)', async () => {
    vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64) Chrome/126' })
    await expect(silentReattach({ transport: 'rawbt' })).resolves.toBeNull()
  })

  it('native: re-attaches by the saved deviceId when the in-app bridge is present', async () => {
    const bridge = fakeNativeBridge()
    vi.stubGlobal('window', { NativePrint: bridge })
    vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Linux; Android 14) Chrome/126' })
    const transport = await silentReattach({
      transport: 'native',
      deviceId: '66:22:AA:01:02:03',
      label: 'RPP58',
    })
    expect(transport?.kind).toBe('native')
    expect(bridge.calls.connect).toEqual([{ deviceId: '66:22:AA:01:02:03' }])
  })

  it('native: resolves null without a saved deviceId, even with the bridge present', async () => {
    vi.stubGlobal('window', { NativePrint: fakeNativeBridge() })
    await expect(silentReattach({ transport: 'native' })).resolves.toBeNull()
  })

  it('native: resolves null (not throw) when the bridge rejects the connect', async () => {
    const bridge = fakeNativeBridge()
    bridge.connect = async () => {
      throw new Error('device out of range')
    }
    vi.stubGlobal('window', { NativePrint: bridge })
    await expect(
      silentReattach({ transport: 'native', deviceId: 'gone', label: 'RPP58' }),
    ).resolves.toBeNull()
  })

  it('BLE is NEVER silently reattached — architecturally impossible (no persisted grant), not a bug', async () => {
    // No navigator.bluetooth stub at all: if silentReattach ever called requestBlePrinter() here,
    // this would throw (no such API) instead of resolving null — the assertion below is the guard.
    vi.stubGlobal('navigator', {})
    await expect(silentReattach({ transport: 'ble' })).resolves.toBeNull()
  })
})
