/**
 * mobile-shots — visual verification of the Native Console Android phone re-fit (390×844).
 *
 * Runs against a LOCAL `npm run dev` server in dev-auth mode with every /api/v1/** call
 * intercepted and answered from fixtures below (no backend needed): seeds a dev company
 * session in localStorage, then walks every phone screen in two passes (light/en and
 * dark/id), including the More sheet and the claim-decision sheet.
 *
 *   npm run dev        (terminal 1)
 *   node scripts/mobile-shots.mjs [outDir]   (terminal 2)
 *
 * Dev-auth grants all four roles, so the MANAGER tab bar renders everywhere; the
 * employee-only tab set differs only in tab items (same component) and is covered by the
 * shouldMountTabBar/persona unit + manual matrix instead.
 */
import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright-core'

const BASE = process.env.SHOT_BASE ?? 'http://localhost:5173'
const OUT = process.argv[2] ?? 'shots-mobile'

// ── Fixtures (IDR, minor units = whole rupiah) ────────────────────────────────

const COMPANY = {
  companyId: 'c0000000-0000-0000-0000-000000000001',
  name: 'Warung Kemang',
  baseCurrency: 'IDR',
  defaultLanguage: 'id',
  businessId: 'b0000000-0000-0000-0000-000000000001',
  divisionId: null,
  planTier: 'FULL',
}

const PROFILE = {
  employeeId: 'e1',
  fullName: 'Rina Puspita',
  ptkpStatus: 'TK/0',
  status: 'ACTIVE',
  maskedNik: '3174••••••••0042',
  maskedBankAccount: 'BCA ••••7731',
  hasNpwp: false,
  maskedNpwp: null,
  assignments: [
    { id: 'a1', orgUnitId: 'o1', role: 'Kasir', effectiveFrom: '2025-01-01', effectiveTo: '9999-12-31' },
  ],
  contracts: [],
}

const PAYSLIP_LINES = [
  { componentKey: 'BASIC_SALARY', kind: 'EARNING', bearer: 'EMPLOYEE', amountMinor: 7000000, currency: 'IDR', illustrative: false, ruleVersion: 'ID-2026.1' },
  { componentKey: 'POSITION_ALLOWANCE', kind: 'EARNING', bearer: 'EMPLOYEE', amountMinor: 1200000, currency: 'IDR', illustrative: false, ruleVersion: 'ID-2026.1' },
  { componentKey: 'MEAL_ALLOWANCE', kind: 'EARNING', bearer: 'EMPLOYEE', amountMinor: 600000, currency: 'IDR', illustrative: false, ruleVersion: 'ID-2026.1' },
  { componentKey: 'BPJS_KES_EMP', kind: 'DEDUCTION', bearer: 'EMPLOYEE', amountMinor: 88000, currency: 'IDR', illustrative: false, ruleVersion: 'ID-2026.1' },
  { componentKey: 'BPJS_JHT_EMP', kind: 'DEDUCTION', bearer: 'EMPLOYEE', amountMinor: 176000, currency: 'IDR', illustrative: false, ruleVersion: 'ID-2026.1' },
  { componentKey: 'PPH21', kind: 'DEDUCTION', bearer: 'EMPLOYEE', amountMinor: 440000, currency: 'IDR', illustrative: false, ruleVersion: 'ID-2026.1' },
]

const payslipDetail = (runId, period, illustrative) => ({
  runId, period, runSeq: 1, runType: 'REGULAR', currency: 'IDR',
  grossMinor: 8800000, deductionMinor: 704000, netMinor: 8096000,
  illustrative, lines: PAYSLIP_LINES,
})
const PAYSLIPS = [
  { runId: 'r1', period: '2026-07', runSeq: 1, postedAt: '2026-08-01', lineCount: 6, illustrative: false },
  { runId: 'r2', period: '2026-06', runSeq: 1, postedAt: '2026-07-01', lineCount: 6, illustrative: false },
  { runId: 'r3', period: '2026-05', runSeq: 1, postedAt: '2026-06-01', lineCount: 6, illustrative: true },
]

const page1 = (content) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })

const MY_CLAIMS = [
  { id: 'cl1', status: 'SUBMITTED', amountMinor: 340000, currency: 'IDR', expenseDate: '2026-08-05', merchant: 'Toko Sinar Jaya', categoryName: 'Perlengkapan', reimbursementMethod: 'PAYROLL', decidedBy: null, decidedAt: null, decisionComment: null },
  { id: 'cl2', status: 'APPROVED', amountMinor: 85000, currency: 'IDR', expenseDate: '2026-08-02', merchant: 'Grab', categoryName: 'Transport', reimbursementMethod: 'DIRECT', decidedBy: 'budi@kemang.id', decidedAt: '2026-08-03T02:00:00Z', decisionComment: null },
  { id: 'cl3', status: 'REFUSED', amountMinor: 220000, currency: 'IDR', expenseDate: '2026-07-24', merchant: null, categoryName: 'Perlengkapan', reimbursementMethod: 'PAYROLL', decidedBy: 'budi@kemang.id', decidedAt: '2026-07-25T02:00:00Z', decisionComment: 'No receipt attached.' },
]

const INBOX = [
  { id: 'ic1', employeeId: 'e1', employeeName: 'Rina Puspita', status: 'SUBMITTED', amountMinor: 340000, currency: 'IDR', expenseDate: '2026-08-05', merchant: 'Toko Sinar Jaya', categoryName: 'Perlengkapan', orgUnitId: 'o1', reimbursementMethod: 'PAYROLL', decidedBy: null, decidedAt: null, decisionComment: null },
  { id: 'ic2', employeeId: 'e2', employeeName: 'Agus Prasetyo', status: 'SUBMITTED', amountMinor: 62000, currency: 'IDR', expenseDate: '2026-08-05', merchant: 'Gojek', categoryName: 'Transport', orgUnitId: 'o1', reimbursementMethod: 'DIRECT', decidedBy: null, decidedAt: null, decisionComment: null },
  { id: 'ic3', employeeId: 'e3', employeeName: 'Sari Wulandari', status: 'SUBMITTED', amountMinor: 156000, currency: 'IDR', expenseDate: '2026-08-04', merchant: 'Kopi Kenangan', categoryName: 'Konsumsi rapat', orgUnitId: 'o2', reimbursementMethod: 'PAYROLL', decidedBy: null, decidedAt: null, decisionComment: null },
]
const INBOX_DETAIL = {
  id: 'ic1', employeeId: 'e1', categoryId: 'cat1', orgUnitId: 'o1', status: 'SUBMITTED',
  amountMinor: 340000, currency: 'IDR', expenseDate: '2026-08-05', merchant: 'Toko Sinar Jaya',
  note: 'Beli lakban dan kantong plastik.', reimbursementMethod: 'PAYROLL',
  reimbursementRunId: null, settledAt: null, approvedAt: null, decidedBy: null, decidedAt: null, decisionComment: null,
}

const pnlFor = (period) => {
  const m = Number(period.slice(5, 7)) || 1
  const revenue = 400000000 + m * 12000000
  const expense = Math.round(revenue * 0.714)
  return { period, revenueMinor: revenue, expenseMinor: expense, netMinor: revenue - expense, currency: 'IDR', usesIllustrativeRules: false }
}

const OUTLETS = (period) => ({
  period, currency: 'IDR',
  outlets: [
    { businessId: 'ou1', revenueMinor: 8940000, outletName: 'Kemang' },
    { businessId: 'ou2', revenueMinor: 6210500, outletName: 'Senopati' },
    { businessId: 'ou3', revenueMinor: 3312000, outletName: 'Cipete' },
  ],
})

const INCOME = (period) => ({
  period, currency: 'IDR',
  revenueLines: [
    { accountCode: '4-1000', accountType: 'REVENUE', netMinor: 452000000, currency: 'IDR' },
    { accountCode: '4-1100', accountType: 'REVENUE', netMinor: 34200000, currency: 'IDR' },
  ],
  expenseLines: [
    { accountCode: '5-1000', accountType: 'EXPENSE', netMinor: 198400000, currency: 'IDR' },
    { accountCode: '6-1000', accountType: 'EXPENSE', netMinor: 84200000, currency: 'IDR' },
    { accountCode: '6-2000', accountType: 'EXPENSE', netMinor: 36000000, currency: 'IDR' },
    { accountCode: '6-2100', accountType: 'EXPENSE', netMinor: 14750000, currency: 'IDR' },
    { accountCode: '6-3000', accountType: 'EXPENSE', netMinor: 13800000, currency: 'IDR' },
  ],
  totalRevenueMinor: 486200000, totalExpenseMinor: 347150000, netMinor: 139050000,
  usesIllustrativeRules: false,
})

const CLOSES = [
  { closeId: 'x1', period: '2026-07', baseCurrency: 'IDR', firstClose: false, reconciled: true, usesIllustrativeRules: false },
  { closeId: 'x2', period: '2026-06', baseCurrency: 'IDR', firstClose: false, reconciled: true, usesIllustrativeRules: true },
  { closeId: 'x3', period: '2026-05', baseCurrency: 'IDR', firstClose: false, reconciled: false, usesIllustrativeRules: false },
  { closeId: 'x4', period: '2026-04', baseCurrency: 'IDR', firstClose: true, reconciled: true, usesIllustrativeRules: false },
]

const agingRow = (id, name, b) => ({
  customerId: id, customerName: name,
  currentMinor: b[0], overdue1To30Minor: b[1], overdue31To60Minor: b[2], overdue61To90Minor: b[3], overdue90PlusMinor: b[4],
  outstandingMinor: b.reduce((a, v) => a + v, 0),
})
const AR_AGING = (asOf) => {
  const rows = [
    agingRow('cu1', 'PT Andalan Catering', [34200000, 0, 0, 0, 0]),
    agingRow('cu2', 'Koperasi Karyawan BNI', [0, 12800000, 0, 0, 0]),
    agingRow('cu3', 'Event Organizer Ranu', [4200000, 0, 3600000, 0, 1800000]),
    agingRow('cu4', 'PT Mitra Sehat', [21600000, 2500000, 0, 1200000, 0]),
  ]
  const totals = rows.reduce(
    (t, r) => ({
      currentMinor: t.currentMinor + r.currentMinor,
      overdue1To30Minor: t.overdue1To30Minor + r.overdue1To30Minor,
      overdue31To60Minor: t.overdue31To60Minor + r.overdue31To60Minor,
      overdue61To90Minor: t.overdue61To90Minor + r.overdue61To90Minor,
      overdue90PlusMinor: t.overdue90PlusMinor + r.overdue90PlusMinor,
      outstandingMinor: t.outstandingMinor + r.outstandingMinor,
    }),
    { currentMinor: 0, overdue1To30Minor: 0, overdue31To60Minor: 0, overdue61To90Minor: 0, overdue90PlusMinor: 0, outstandingMinor: 0 },
  )
  return { asOf, currency: 'IDR', rows, totals }
}
const AP_AGING = (asOf) => {
  const base = AR_AGING(asOf)
  return {
    ...base,
    rows: [
      agingRow('v1', 'CV Sumber Pangan', [18600000, 0, 0, 0, 0]),
      agingRow('v2', 'PT Kemasan Prima', [0, 7400000, 0, 0, 2100000]),
      agingRow('v3', 'Tirta Segar', [3200000, 0, 0, 0, 0]),
      agingRow('v4', 'PT Gas Nusantara', [5600000, 0, 6400000, 0, 0]),
    ],
  }
}

const INVOICES = [
  { id: 'i1', invoiceNumber: 'INV-2026-0881', customerId: 'cu1', customerName: 'PT Andalan Catering', status: 'ISSUED', issueDate: '2026-07-20', dueDate: '2026-08-12', currency: 'IDR', totalMinor: 34200000, paidMinor: 0, outstandingMinor: 34200000 },
  { id: 'i2', invoiceNumber: 'INV-2026-0864', customerId: 'cu2', customerName: 'Koperasi Karyawan BNI', status: 'PARTIALLY_PAID', issueDate: '2026-07-08', dueDate: '2026-07-26', currency: 'IDR', totalMinor: 21400000, paidMinor: 8600000, outstandingMinor: 12800000 },
  { id: 'i3', invoiceNumber: 'INV-2026-0850', customerId: 'cu3', customerName: 'Event Organizer Ranu', status: 'PAID', issueDate: '2026-07-01', dueDate: '2026-07-15', currency: 'IDR', totalMinor: 9600000, paidMinor: 9600000, outstandingMinor: 0 },
  { id: 'i4', invoiceNumber: 'INV-2026-0892', customerId: 'cu4', customerName: 'PT Mitra Sehat', status: 'ISSUED', issueDate: '2026-08-03', dueDate: '2026-08-26', currency: 'IDR', totalMinor: 21600000, paidMinor: 0, outstandingMinor: 21600000 },
]
const BILLS = INVOICES.map((i, n) => ({
  id: `b${n + 1}`,
  billNumber: i.invoiceNumber.replace('INV', 'BILL'),
  vendorId: `v${n + 1}`,
  vendorName: ['CV Sumber Pangan', 'PT Kemasan Prima', 'Tirta Segar', 'PT Gas Nusantara'][n],
  status: n === 0 ? 'POSTED' : i.status === 'ISSUED' ? 'POSTED' : i.status,
  billDate: i.issueDate, dueDate: i.dueDate, currency: 'IDR',
  totalMinor: i.totalMinor, paidMinor: i.paidMinor, outstandingMinor: i.outstandingMinor,
}))

const TEAM = [
  { id: 'u1', username: 'budi', email: 'budi@kemang.id', roles: ['owner'], enabled: true, outletCount: 0 },
  { id: 'u2', username: 'rina', email: 'rina@kemang.id', roles: ['cashier'], enabled: true, outletCount: 1 },
  { id: 'u3', username: 'sari', email: 'sari@kemang.id', roles: ['cashier'], enabled: true, outletCount: 0 },
  { id: 'u4', username: 'dewi', email: 'dewi@kemang.id', roles: ['manager'], enabled: true, outletCount: 2 },
  { id: 'u5', username: 'maya', email: 'maya@kemang.id', roles: ['employee'], enabled: false, outletCount: 0 },
]

// ── Route table — first match wins; functions get the URL object ─────────────

const ROUTES = [
  ['/api/v1/me/profile', () => PROFILE],
  [/\/api\/v1\/me\/payslips\/(r\d)/, (u, m) => payslipDetail(m[1], m[1] === 'r1' ? '2026-07' : m[1] === 'r2' ? '2026-06' : '2026-05', m[1] === 'r3')],
  ['/api/v1/me/payslips', () => PAYSLIPS],
  ['/api/v1/me/sales', () => ({ period: '2026-08', salesMinor: 42180000, currency: 'IDR', commissionBasisPoints: 50, commissionEstimateMinor: 210900 })],
  ['/api/v1/me/leave-balance', () => ({ year: 2026, grantedDays: 12, adjustmentDays: 0, usedDays: 4, remaining: 8 })],
  ['/api/v1/me/leave-requests', () => page1([
    { id: 'lr1', leaveType: 'ANNUAL', startDate: '2026-08-12', endDate: '2026-08-14', days: 3, status: 'APPROVED', decidedBy: 'budi@kemang.id', decidedAt: '2026-08-01T02:00:00Z', decisionNote: null },
    { id: 'lr2', leaveType: 'ANNUAL', startDate: '2026-08-28', endDate: '2026-08-28', days: 1, status: 'SUBMITTED', decidedBy: null, decidedAt: null, decisionNote: null },
    { id: 'lr3', leaveType: 'SICK', startDate: '2026-07-19', endDate: '2026-07-19', days: 1, status: 'REJECTED', decidedBy: 'budi@kemang.id', decidedAt: '2026-07-20T02:00:00Z', decisionNote: 'Perlu surat dokter.' },
  ])],
  ['/api/v1/me/overtime-entries', () => page1([
    { id: 'ot1', workDate: '2026-08-02', minutes: 150, dayKind: 'WEEKDAY', status: 'APPROVED', decidedBy: 'budi@kemang.id', decidedAt: '2026-08-03T02:00:00Z', decisionNote: null },
  ])],
  ['/api/v1/me/expense-claims', () => page1(MY_CLAIMS)],
  [/\/api\/v1\/expense-claims\/[^/]+\/receipt/, () => ({ status: 404 })],
  [/\/api\/v1\/expense-claims\/[^/]+$/, () => INBOX_DETAIL],
  ['/api/v1/expense-claims', () => page1(INBOX)],
  ['/api/v1/pnl/outlets', (u) => OUTLETS(u.searchParams.get('period') ?? '2026-08')],
  ['/api/v1/pnl', (u) => pnlFor(u.searchParams.get('period') ?? '2026-08')],
  ['/api/v1/statements/income', (u) => INCOME(u.searchParams.get('period') ?? '2026-08')],
  ['/api/v1/closes', () => CLOSES],
  ['/api/v1/ar/aging', (u) => AR_AGING(u.searchParams.get('asOf') ?? '2026-08-07')],
  ['/api/v1/ap/aging', (u) => AP_AGING(u.searchParams.get('asOf') ?? '2026-08-07')],
  ['/api/v1/invoices', () => INVOICES],
  ['/api/v1/ap/bills', () => BILLS],
  ['/api/v1/users/me/pages', () => ({ mode: 'ALL', pageKeys: [] })],
  ['/api/v1/users', () => TEAM],
  ['/api/v1/org-units', () => []],
  ['/api/v1/outlets', () => []],
]

function resolveFixture(url) {
  const u = new URL(url)
  for (const [pattern, fn] of ROUTES) {
    if (typeof pattern === 'string') {
      if (u.pathname === pattern) return fn(u)
    } else {
      const m = u.pathname.match(pattern)
      if (m) return fn(u, m)
    }
  }
  return {}
}

// ── Walk ─────────────────────────────────────────────────────────────────────

const SCREENS = [
  ['home', '/'],
  ['income', '/statements/income'],
  ['team', '/team'],
  ['inbox', '/expenses'],
  ['close', '/close'],
  ['ar-aging', '/ar/aging'],
  ['ap-aging', '/ap/aging'],
  ['invoices', '/invoices'],
  ['bills', '/bills'],
  ['me-home', '/me'],
  ['me-payslips', '/me/payslips'],
  ['me-timeoff', '/me/timeoff'],
  ['me-claims', '/me/expenses'],
]

const browser = await chromium.launch({ channel: 'chrome', headless: true })

for (const pass of [
  { name: 'light-en', theme: 'light', lang: 'en', moreLabel: 'More' },
  { name: 'dark-id', theme: 'dark', lang: 'id', moreLabel: 'Lainnya' },
]) {
  const dir = `${OUT}/${pass.name}`
  mkdirSync(dir, { recursive: true })
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  await ctx.addInitScript(
    ([company, theme, lang]) => {
      localStorage.setItem('native.console.sessions', JSON.stringify([company]))
      localStorage.setItem('native.console.theme', theme)
      localStorage.setItem('native.console.lang', lang)
    },
    [COMPANY, pass.theme, pass.lang],
  )
  await ctx.route('**/api/v1/**', async (route) => {
    const fx = resolveFixture(route.request().url())
    if (fx && fx.status === 404) return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fx) })
  })
  const page = await ctx.newPage()
  page.on('pageerror', (e) => console.log(`[${pass.name}] PAGEERROR`, String(e.message).slice(0, 160)))

  for (const [name, path] of SCREENS) {
    await page.goto(`${BASE}${path}`, { waitUntil: 'load' })
    await page.waitForTimeout(1200)
    await page.screenshot({ path: `${dir}/${name}.png`, fullPage: false })
    console.log(`[${pass.name}] ${name} ok (${page.url().replace(BASE, '') || '/'})`)
  }

  // More sheet (manager persona)
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1000)
  await page.getByRole('button', { name: pass.moreLabel, exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(600)
  await page.screenshot({ path: `${dir}/more-sheet.png` })
  console.log(`[${pass.name}] more-sheet ok`)

  // Claim decision sheet
  await page.goto(`${BASE}/expenses`, { waitUntil: 'load' })
  await page.waitForTimeout(1200)
  await page.getByText('Rina Puspita').first().click({ timeout: 8000 })
  await page.waitForTimeout(900)
  await page.screenshot({ path: `${dir}/decision-sheet.png` })
  console.log(`[${pass.name}] decision-sheet ok`)

  // Expanded payslip (sign-flip lines + Cetak)
  await page.goto(`${BASE}/me/payslips`, { waitUntil: 'load' })
  await page.waitForTimeout(1200)
  await page.getByText('2026-07', { exact: true }).first().click({ timeout: 8000 })
  await page.waitForTimeout(900)
  await page.screenshot({ path: `${dir}/payslip-open.png`, fullPage: true })
  console.log(`[${pass.name}] payslip-open ok`)

  await ctx.close()
}

await browser.close()
console.log('DONE — shots in', OUT)
