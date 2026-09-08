import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { ACCOUNT_LABEL_KEYS, accountLabel, accountLabelMap } from '../accountLabels'
import { en } from '@/i18n/locales/en'

/**
 * The statement pages name every GL account from ACCOUNT_LABEL_KEYS, so an account that finance-
 * service seeds without an entry there silently regresses to a bare code on Neraca / Laba Rugi /
 * Arus Kas — exactly the defect this map exists to fix. These tests are the gate: they read the
 * chart-of-accounts seed straight out of the Flyway migrations and fail the build on drift.
 */

// finance-service's migrations, relative to frontend/console/src/features/statements/__tests__.
const MIGRATION_DIR = fileURLToPath(
  new URL('../../../../../../services/finance-service/src/main/resources/db/migration', import.meta.url),
)

/**
 * Every account_code the migrations INSERT into `chart_of_account` (global reference data — the
 * table has no company_id, so this seed IS the whole chart). Codes are the four-digit natural key;
 * comment lines are stripped first so a `--` example can never be read as a real row.
 */
function seededAccountCodes(): string[] {
  const codes = new Set<string>()
  for (const file of readdirSync(MIGRATION_DIR).filter((f) => f.endsWith('.sql'))) {
    const sql = readFileSync(`${MIGRATION_DIR}/${file}`, 'utf8')
      .split('\n')
      .map((line) => line.replace(/--.*$/, ''))
      .join('\n')
    // Each INSERT runs from the keyword to its terminating semicolon; inside it, every value tuple
    // opens with the account code, e.g. `('5100', 'Cost of Goods Sold', 'EXPENSE')`.
    for (const insert of sql.matchAll(/INSERT\s+INTO\s+chart_of_account\b[\s\S]*?;/gi)) {
      for (const tuple of insert[0].matchAll(/\(\s*'(\d{4})'\s*,/g)) codes.add(tuple[1])
    }
  }
  return [...codes].sort()
}

describe('chart-of-accounts labels', () => {
  /**
   * FAILS — deliberately — rather than skipping when finance-service isn't beside the console. A
   * gate that quietly turns into a no-op is worse than no gate: `accountLabels.ts` cites this test
   * as the reason it is safe to hardcode the chart, and a green tick would keep saying so while
   * checking nothing. When the polyrepo split lands, replace the SQL scrape with the seeded chart
   * published as data (a generated constant the console imports) so the check travels with the
   * code that depends on it — see [[native-polyrepo-split]].
   */
  it('can see the finance-service migrations it gates on', () => {
    expect(existsSync(MIGRATION_DIR)).toBe(true)
  })

  it('names every account finance-service seeds', () => {
    const seeded = seededAccountCodes()
    // Guards the regex itself: a parse that silently matched nothing would make this test vacuous.
    expect(seeded.length).toBeGreaterThan(50)
    expect(seeded.filter((code) => !ACCOUNT_LABEL_KEYS[code])).toEqual([])
  })

  it('resolves every label key to real copy in the locale bundle', () => {
    const missing = Object.entries(ACCOUNT_LABEL_KEYS).filter(([, key]) => {
      const value = key
        .split('.')
        .reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], en)
      return typeof value !== 'string' || value.length === 0
    })
    expect(missing).toEqual([])
  })

  // 3100 is already "retained earnings — prior years"; the synthetic row must not collide with it.
  it('names the synthetic profit row the balance sheet appends, distinctly from account 3100', () => {
    expect(ACCOUNT_LABEL_KEYS['3000-RETAINED-EARNINGS']).toBe(
      'statements.accounts.accumulatedProfit',
    )
    expect(ACCOUNT_LABEL_KEYS['3100']).not.toBe(ACCOUNT_LABEL_KEYS['3000-RETAINED-EARNINGS'])
  })

  it('returns undefined for an account it does not name, so callers can fall back', () => {
    expect(accountLabel((key) => key, '1900')).toBe('statements.accounts.cash')
    expect(accountLabel((key) => key, '7777')).toBeUndefined()
  })

  // A bare object-literal lookup would resolve these off Object.prototype and hand them to t().
  it('does not mistake an inherited prototype member for an account name', () => {
    for (const code of ['constructor', 'toString', 'valueOf', '__proto__', 'hasOwnProperty']) {
      expect(accountLabel((key) => key, code)).toBeUndefined()
    }
  })

  it('builds a code → name map covering every known account', () => {
    const map = accountLabelMap((key) => key)
    expect(map.size).toBe(Object.keys(ACCOUNT_LABEL_KEYS).length)
    expect(map.get('5100')).toBe('statements.accounts.costOfGoodsSold')
  })
})
