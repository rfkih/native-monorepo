/**
 * Minimal ESC/POS command encoder (ADR 0039 — direct browser→thermal-printer printing).
 *
 * Hand-rolled on purpose: the subset a receipt needs (init, align, emphasis, size, feed, cut,
 * cash-drawer pulse, raw text) is ~15 commands — a dependency would bring a full printer-driver
 * surface we would then have to audit. Commands follow the Epson ESC/POS reference; the cheap
 * Xprinter/Goojprt/EPPOS clones ubiquitous in Indonesian SMBs implement this same core set.
 *
 * Text encoding: receipts are emitted in ASCII. Indonesian (and our en copy) is ASCII-safe except
 * for the odd typographic character, so anything outside 0x20–0x7E is transliterated (dashes,
 * quotes, ×, Rp already ASCII) and finally replaced with '?'. This sidesteps the printer codepage
 * lottery entirely — CP437/CP850 selection on clones is famously unreliable.
 */

const ESC = 0x1b
const GS = 0x1d

/** Typographic characters our formatters/i18n emit, mapped to ASCII before the fallback '?'. */
const TRANSLITERATIONS: Record<string, string> = {
  '—': '--', // em dash
  '–': '-', // en dash
  '‘': "'",
  '’': "'",
  '“': '"',
  '”': '"',
  '…': '...',
  '×': 'x', // multiplication sign (qty)
  '•': '*',
  '·': '.',
  ' ': ' ', // no-break space (Intl currency formatting)
  ' ': ' ', // narrow no-break space (Intl in some locales)
}

/** ASCII-only bytes for a string (transliterate → '?' fallback). Exported for layout width math. */
export function toAscii(text: string): string {
  let out = ''
  for (const ch of text) {
    if (ch >= ' ' && ch <= '~') {
      out += ch
    } else if (TRANSLITERATIONS[ch] !== undefined) {
      out += TRANSLITERATIONS[ch]
    } else if (ch === '\n') {
      out += '\n'
    } else {
      out += '?'
    }
  }
  return out
}

export class EscposEncoder {
  private chunks: number[] = []

  /** ESC @ — reset formatting, clear the print buffer. Always the first command. */
  init(): this {
    this.chunks.push(ESC, 0x40)
    return this
  }

  /** ESC a n — 0 left, 1 center, 2 right. */
  align(mode: 'left' | 'center' | 'right'): this {
    this.chunks.push(ESC, 0x61, mode === 'left' ? 0 : mode === 'center' ? 1 : 2)
    return this
  }

  /** ESC E n — emphasized (bold). */
  bold(on: boolean): this {
    this.chunks.push(ESC, 0x45, on ? 1 : 0)
    return this
  }

  /**
   * GS ! n — character size. Width/height multipliers 1–8; receipts only ever need 1 or 2.
   */
  size(width: 1 | 2, height: 1 | 2): this {
    this.chunks.push(GS, 0x21, ((width - 1) << 4) | (height - 1))
    return this
  }

  /** Raw ASCII text (no newline). */
  text(value: string): this {
    for (let i = 0; i < value.length; i++) {
      this.chunks.push(value.charCodeAt(i) & 0x7f)
    }
    return this
  }

  /** Text + line feed. */
  line(value = ''): this {
    this.text(value)
    this.chunks.push(0x0a)
    return this
  }

  /** ESC d n — feed n lines. */
  feed(lines: number): this {
    this.chunks.push(ESC, 0x64, lines)
    return this
  }

  /**
   * GS V 66 n — partial cut with feed. Universally supported on cutter models; printers without a
   * cutter (58mm clip-fed) simply ignore it.
   */
  cut(): this {
    this.chunks.push(GS, 0x56, 0x42, 0x00)
    return this
  }

  /**
   * ESC p m t1 t2 — cash-drawer kick pulse on pin 2 (the near-universal RJ11 wiring), 100 ms.
   */
  drawerKick(): this {
    this.chunks.push(ESC, 0x70, 0x00, 0x32, 0xfa)
    return this
  }

  build(): Uint8Array {
    return Uint8Array.from(this.chunks)
  }
}
