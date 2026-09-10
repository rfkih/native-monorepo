/**
 * mobile-shots — visual verification of the Native Console Android phone re-fit (390×844).
 *
 * Runs against a LOCAL `npm run dev` server in dev-auth mode with every /api/v1/** call
 * intercepted and answered from fixtures below (no backend needed): seeds a dev company
 * session in localStorage, then walks every phone screen in two passes (light/en and
 * dark/id), including the More screen and the claim-decision sheet.
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

// ── POS fixtures (Native Till Android v2 — the phone till's bill deck) ────────

const menuItem = (id, name, priceMinor, categoryId, stockQuantity = null) => ({
  id, businessId: COMPANY.businessId, name, category: '', categoryId,
  priceMinor, currency: 'IDR', active: true, available: stockQuantity !== 0,
  stockQuantity, unitCostMinor: null, modifierGroups: [], imageUrl: null,
})

const MENU = [
  menuItem('m1', 'Nasi Goreng Spesial', 45000, 'c1', 12),
  menuItem('m2', 'Ayam Bakar Madu', 52000, 'c1', 3),
  menuItem('m3', 'Mie Goreng Jawa', 42000, 'c1', 8),
  menuItem('m4', 'Sate Ayam 10 tusuk', 48000, 'c1', 0),
  menuItem('m5', 'Soto Betawi', 46000, 'c1', 6),
  menuItem('m6', 'Gado-Gado Siram', 38000, 'c1', 9),
  menuItem('m7', 'Es Teh Manis', 12000, 'c2', 40),
  menuItem('m8', 'Kopi Susu Gula Aren', 25000, 'c2', 18),
  menuItem('m9', 'Jus Alpukat', 28000, 'c2', 5),
  menuItem('m10', 'Air Mineral 600ml', 8000, 'c2', 60),
  menuItem('m11', 'Pisang Goreng Keju', 32000, 'c3', 7),
  menuItem('m12', 'Kerupuk Udang', 10000, 'c3', 25),
]

const MENU_CATEGORIES = [
  { id: 'c1', businessId: COMPANY.businessId, name: 'Makanan', displayOrder: 1, active: true },
  { id: 'c2', businessId: COMPANY.businessId, name: 'Minuman', displayOrder: 2, active: true },
  { id: 'c3', businessId: COMPANY.businessId, name: 'Tambahan', displayOrder: 3, active: true },
]

const TABLES = [
  { tableId: 't1', businessId: COMPANY.businessId, label: 'Meja 01', capacity: 4, area: null, active: true, occupied: false },
  { tableId: 't7', businessId: COMPANY.businessId, label: 'Meja 07', capacity: 4, area: null, active: true, occupied: true },
]

const breakdownOf = (subtotal) => ({
  subtotalMinor: subtotal, discountMinor: 0, serviceChargeMinor: 0, taxMinor: 0,
  grandTotalMinor: subtotal, currency: 'IDR', usesIllustrativeRules: false,
  appliedPromotions: [], couponStatus: null, loyaltyRedeemedMinor: 0,
  giftCardAppliedMinor: 0, residualDueMinor: subtotal,
})

const billLine = (id, menuItemId, nameSnapshot, unitPriceMinor, paid = false) => ({
  id, menuItemId, nameSnapshot, unitPriceMinor, modifierDeltaMinor: 0,
  qty: 1, lineTotalMinor: unitPriceMinor, modifiers: [], paid,
})

// Deliberately a PARTIALLY paid bill: it is the state that exercises the deck's whole vocabulary —
// the "Partly paid" badge, a dimmed settled row, and "Still owing" rather than "Total".
const BILL_LINES = [
  billLine('bl1', 'm1', 'Nasi Goreng Spesial', 45000, true),
  billLine('bl2', 'm2', 'Ayam Bakar Madu', 52000),
  billLine('bl3', 'm7', 'Es Teh Manis', 12000),
  billLine('bl4', 'm8', 'Kopi Susu Gula Aren', 25000),
  billLine('bl5', 'm11', 'Pisang Goreng Keju', 32000),
]
const BILL_UNPAID = BILL_LINES.filter((l) => !l.paid).reduce((s, l) => s + l.lineTotalMinor, 0)

const BILL = {
  id: 'b1', businessId: COMPANY.businessId, tableId: 't7', guestLabel: 'Meja 07',
  status: 'OPEN', currency: 'IDR', discountMinor: null, saleId: null,
  lines: BILL_LINES, breakdown: breakdownOf(BILL_UNPAID),
}

const BILL_SUMMARIES = [
  {
    id: 'b1', businessId: COMPANY.businessId, tableId: 't7', guestLabel: 'Meja 07',
    status: 'OPEN', currency: 'IDR', discountMinor: null,
    runningTotalMinor: BILL_UNPAID, lineCount: BILL_LINES.length,
  },
]

const REGISTER_SESSION = {
  id: 'rs1', businessId: COMPANY.businessId, status: 'OPEN', businessDate: '2026-08-07',
  openedAt: '2026-08-07T00:02:00Z', openingFloatMinor: 500000, currency: 'IDR',
  closedAt: null, cashSalesMinor: null, cashRefundsMinor: null,
  expectedCashMinor: null, countedCashMinor: null, overShortMinor: null,
}

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

// Statement lines carry codes the console can NAME (accountLabels.ts) — an unlabelled code would
// fail the real build, so the fixture uses the chart's own codes. Totals equal pnlFor(period) so
// the twelve-month chart (/api/v1/pnl) and the tapped month's statement never disagree.
const INCOME = (period) => {
  const { revenueMinor: rev, expenseMinor: exp } = pnlFor(period)
  const line = (accountCode, accountType, share, total) => ({ accountCode, accountType, netMinor: Math.round(total * share), currency: 'IDR' })
  const exact = (lines, total) => { lines[0].netMinor += total - lines.reduce((a, l) => a + l.netMinor, 0); return lines }
  return {
    period, currency: 'IDR',
    revenueLines: exact([line('4000', 'REVENUE', 0.93, rev), line('4100', 'REVENUE', 0.07, rev)], rev),
    expenseLines: exact([
      line('5100', 'EXPENSE', 0.57, exp), line('5300', 'EXPENSE', 0.24, exp), line('5500', 'EXPENSE', 0.10, exp),
      line('6000', 'EXPENSE', 0.05, exp), line('6100', 'EXPENSE', 0.04, exp),
    ], exp),
    totalRevenueMinor: rev, totalExpenseMinor: exp, netMinor: rev - exp,
    usesIllustrativeRules: false,
  }
}

// Neraca per month (codes as the console names them: 1000 bank, 1200 receivables, 1250 platform
// receivable, 1100 inventory, 1300/1310 VAT, 1400 prepaid, 1500/1590 equipment, 2000 payables,
// 2200 VAT payable, 2500 gift cards, 3000 capital, 3100 prior years): the bank grows, equipment
// depreciates, gift-card float is redeemed; 1250 sits negative — allowlisted, so no red banner —
// and 1310 is a zero row so the "show zero balances" toggle has something to show. This year's
// profit is the plug that balances.
const BALANCE = (asOf) => {
  const m = Number(asOf.slice(5, 7)) || 1
  const row = (accountType) => (accountCode, balanceMinor) => ({ accountCode, accountType, balanceMinor, currency: 'IDR' })
  const a = row('ASSET'), l = row('LIABILITY'), e = row('EQUITY')
  const sum = (lines) => lines.reduce((t, x) => t + x.balanceMinor, 0)
  const assetLines = [
    a('1000', 318000000 + m * 21000000), a('1200', 27500000 + m * 400000), a('1250', -6400000),
    a('1100', 64000000 - m * 900000), a('1300', 12800000 + m * 250000), a('1310', 0), a('1400', 18000000),
    a('1500', 240000000), a('1590', -(m * 4000000)),
  ]
  const liabilityLines = [l('2000', 48200000 + m * 700000), l('2200', 12100000 + m * 300000), l('2500', 60000000 - m * 2500000)]
  const capital = 300000000
  const prior = 96000000
  const retained = sum(assetLines) - sum(liabilityLines) - capital - prior
  return {
    asOf, currency: 'IDR', assetLines, liabilityLines,
    equityLines: [e('3000', capital), e('3100', prior), e('3000-RETAINED-EARNINGS', retained)],
    totalAssetsMinor: sum(assetLines), totalLiabilitiesMinor: sum(liabilityLines),
    totalEquityMinor: capital + prior + retained, totalLiabilitiesAndEquityMinor: sum(assetLines),
    retainedEarningsMinor: retained, usesIllustrativeRules: false,
  }
}

// Arus kas (indirect): profit + working-capital lines, an equipment purchase every third month,
// a steady loan repayment; the movement always reconciles to the ledger here.
const CASHFLOW = (period) => {
  const m = Number(period.slice(5, 7)) || 1
  const net = pnlFor(period).netMinor
  const line = (accountCode, accountType, amountMinor) => ({ accountCode, accountType, amountMinor })
  const sum = (lines) => lines.reduce((t, x) => t + x.amountMinor, 0)
  const operatingLines = [line('1590', 'ASSET', 4000000), line('1200', 'ASSET', -400000), line('1100', 'ASSET', 900000), line('2000', 'LIABILITY', 700000), line('2200', 'LIABILITY', 300000)]
  const investingLines = m % 3 === 0 ? [line('1500', 'ASSET', -18000000)] : []
  const financingLines = [line('2500', 'LIABILITY', -2500000)]
  const op = net + sum(operatingLines), inv = sum(investingLines), fin = sum(financingLines)
  return {
    period, currency: 'IDR', netIncomeMinor: net,
    operatingLines, cashFromOperatingMinor: op, investingLines, cashFromInvestingMinor: inv,
    financingLines, cashFromFinancingMinor: fin,
    netChangeInCashMinor: op + inv + fin, cashMovementMinor: op + inv + fin, reconciled: true,
    usesIllustrativeRules: false,
  }
}

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
  ['/api/v1/statements/balance-sheet', (u) => BALANCE(u.searchParams.get('asOf') ?? '2026-08')],
  ['/api/v1/statements/cash-flow', (u) => CASHFLOW(u.searchParams.get('period') ?? '2026-08')],
  ['/api/v1/closes', () => CLOSES],
  ['/api/v1/ar/aging', (u) => AR_AGING(u.searchParams.get('asOf') ?? '2026-08-07')],
  ['/api/v1/ap/aging', (u) => AP_AGING(u.searchParams.get('asOf') ?? '2026-08-07')],
  ['/api/v1/invoices', () => INVOICES],
  ['/api/v1/ap/bills', () => BILLS],
  // POS (Native Till Android v2). `/api/v1/bills/b1` must precede `/api/v1/bills`.
  ['/api/v1/menu/categories', () => MENU_CATEGORIES],
  ['/api/v1/menu', () => MENU],
  ['/api/v1/tables', () => TABLES],
  ['/api/v1/pricing/effective-rules', () => ({ serviceChargeBp: 0, taxBp: 0, serviceChargeInTaxBase: false, usesIllustrativeRules: false })],
  // The live quote must echo the cart it was POSTed, or every shot of the deck reads "Rp 0" while
  // the lines above it clearly are not free.
  ['/api/v1/orders/quote', (u, m, req) => {
    const lines = req?.postDataJSON?.()?.lines ?? []
    const subtotal = lines.reduce(
      (sum, l) => sum + (MENU.find((i) => i.id === l.menuItemId)?.priceMinor ?? 0) * (l.qty ?? 0),
      0,
    )
    return breakdownOf(subtotal)
  }],
  ['/api/v1/orders/item-popularity', () => []],
  ['/api/v1/orders/item-sales', () => []],
  ['/api/v1/orders', () => []],
  ['/api/v1/register-sessions/current', () => REGISTER_SESSION],
  [/\/api\/v1\/bills\/[^/]+\/attachments$/, () => []],
  [/\/api\/v1\/bills\/[^/]+$/, () => BILL],
  ['/api/v1/bills', () => BILL_SUMMARIES],
  ['/api/v1/users/me/pages', () => ({ mode: 'ALL', pageKeys: [] })],
  ['/api/v1/users', () => TEAM],
  ['/api/v1/org-units', () => []],
  // One real outlet, id == COMPANY.businessId: the POS OutletGate blocks the whole till without
  // one, and the session's businessId has to be the outlet the terminal is ringing on.
  ['/api/v1/outlets', () => [{ id: COMPANY.businessId, name: 'Kemang' }]],
  ['/api/v1/users/me/outlets', () => []],
  // ADR 0076 — the home page's overdue-payout nudge. It renders nothing when the list is empty,
  // which is what we want in a shot; without a fixture it fell through to the `{}` default and
  // `overdue.map` threw, taking the whole dashboard down behind the error boundary.
  ['/api/v1/platform-settlements/overdue', () => []],
]

function resolveFixture(url, req) {
  const u = new URL(url)
  for (const [pattern, fn] of ROUTES) {
    if (typeof pattern === 'string') {
      if (u.pathname === pattern) return fn(u, null, req)
    } else {
      const m = u.pathname.match(pattern)
      if (m) return fn(u, m, req)
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
  { name: 'light-en', theme: 'light', lang: 'en', moreLabel: 'More', ordersLabel: 'Orders',
    lineChart: 'Line chart', tabs: { bs: 'Balance', cf: 'Cash flow', exp: 'Expenses' } },
  { name: 'dark-id', theme: 'dark', lang: 'id', moreLabel: 'Lainnya', ordersLabel: 'Pesanan',
    lineChart: 'Grafik garis', tabs: { bs: 'Neraca', cf: 'Arus kas', exp: 'Biaya' } },
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
    const fx = resolveFixture(route.request().url(), route.request())
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

  // More SCREEN (manager persona) — a route since ADR 0078, not a sheet.
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1000)
  // A LINK now, not a button — More is a routed screen since ADR 0078.
  await page.getByRole('link', { name: pass.moreLabel, exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(600)
  await page.screenshot({ path: `${dir}/more-screen.png` })
  console.log(`[${pass.name}] more-screen ok`)

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
  // The row label is `periodLabel(period, locale)` ("July 2026" / "Juli 2026"), not the raw
  // `2026-07` this used to match — so it broke in BOTH passes the moment the label was localised.
  // Click the first payslip row instead: locale-agnostic, and it is the row we want either way.
  await page.locator('button:has-text("2026")').first().click({ timeout: 8000 })
  await page.waitForTimeout(900)
  await page.screenshot({ path: `${dir}/payslip-open.png`, fullPage: true })
  console.log(`[${pass.name}] payslip-open ok`)


  // ── Laporan: the three statements as one phone screen (Native Laporan) ─────
  // The chart IS the period control — a tapped column moves the whole report — so the shots pair
  // the first tab with a month change and the line variant, then the two sheets, then each tab.
  await page.goto(`${BASE}/statements/income`, { waitUntil: 'load' })
  await page.waitForTimeout(1800)
  await page.screenshot({ path: `${dir}/laporan-pnl.png`, fullPage: true })
  console.log(`[${pass.name}] laporan-pnl ok`)
  // Chart columns are the only pressed-state buttons that carry a title (month · value).
  await page.locator('button[title][aria-pressed="false"]').nth(2).click({ timeout: 8000 })
  await page.waitForTimeout(1200)
  await page.screenshot({ path: `${dir}/laporan-pnl-month.png` })
  console.log(`[${pass.name}] laporan-pnl-month ok`)
  await page.getByRole('button', { name: pass.lineChart, exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(500)
  await page.screenshot({ path: `${dir}/laporan-pnl-line.png` })
  console.log(`[${pass.name}] laporan-pnl-line ok`)
  // Drill-down sheet from the hero figure; BACK closes it (ADR 0075), not a tap on the scrim.
  await page.getByTestId('laporan-hero').click({ timeout: 8000 })
  await page.waitForTimeout(800)
  await page.screenshot({ path: `${dir}/laporan-detail-sheet.png` })
  console.log(`[${pass.name}] laporan-detail-sheet ok`)
  await page.goBack()
  await page.waitForTimeout(600)
  // The tapped month and the line chart travel with the tab (they live in the URL) — the shot of
  // each tab must show the SAME earlier month, or a tab switch has silently reset the reader.
  for (const [key, label] of Object.entries(pass.tabs)) {
    await page.getByRole('tab', { name: label, exact: true }).click({ timeout: 8000 })
    await page.waitForTimeout(1800)
    const url = page.url().replace(BASE, '')
    if (!/period=\d{4}-\d{2}/.test(url) || !url.includes('chart=line')) throw new Error(`tab ${key} lost the selection: ${url}`)
    await page.screenshot({ path: `${dir}/laporan-${key}.png`, fullPage: true })
    console.log(`[${pass.name}] laporan-${key} ok (${url})`)
  }
  // Export sheet — the title row's action.
  await page.getByTestId('laporan-export').click({ timeout: 8000 })
  await page.waitForTimeout(800)
  await page.screenshot({ path: `${dir}/laporan-export-sheet.png` })
  console.log(`[${pass.name}] laporan-export-sheet ok`)
  await page.goBack()
  await page.waitForTimeout(400)

  // ── POS: the phone bill deck (Native Till Android v2) ──────────────────────
  // The deck is the redesign's whole thesis — the bill lives on screen instead of inside a sheet —
  // so the shots have to show it in all four states it can be in, for BOTH data owners.
  await page.goto(`${BASE}/pos`, { waitUntil: 'load' })
  await page.waitForTimeout(1600)
  await page.screenshot({ path: `${dir}/pos-deck-empty.png` })
  console.log(`[${pass.name}] pos-deck-empty ok`)

  // Ring three items — the catalog stays visible the whole time, which is the point.
  for (const item of ['Nasi Goreng Spesial', 'Es Teh Manis', 'Kopi Susu Gula Aren']) {
    await page.getByRole('button', { name: new RegExp(item) }).first().click({ timeout: 8000 })
    await page.waitForTimeout(350)
  }
  // The live quote is debounced; shooting before it settles catches the dimmed pending figure.
  await page.waitForTimeout(1600)
  // Clicking tiles auto-scrolls the catalog — put it back at the top so the shot shows the grid
  // the way a cashier opens it.
  await page.mouse.move(195, 350)
  await page.mouse.wheel(0, -3000)
  await page.waitForTimeout(500)
  await page.screenshot({ path: `${dir}/pos-deck-peek.png` })
  console.log(`[${pass.name}] pos-deck-peek ok`)

  await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 })
  await page.waitForTimeout(700)
  await page.screenshot({ path: `${dir}/pos-deck-expanded.png` })
  console.log(`[${pass.name}] pos-deck-expanded ok`)
  await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 })
  await page.waitForTimeout(500)

  // Same component, other data owner: a partially-paid open bill (BillDetail renders the deck).
  // The switcher lives in the till menu now (and, in bill mode, as the deck's first chip).
  await page.getByTestId('pos-till-menu').click({ timeout: 8000 })
  await page.waitForTimeout(400)
  await page.getByRole('menuitem', { name: pass.ordersLabel, exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(600)
  await page.getByText('Meja 07').first().click({ timeout: 8000 })
  await page.waitForTimeout(1400)
  await page.screenshot({ path: `${dir}/pos-bill-peek.png` })
  console.log(`[${pass.name}] pos-bill-peek ok`)

  await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 })
  await page.waitForTimeout(700)
  await page.screenshot({ path: `${dir}/pos-bill-expanded.png` })
  console.log(`[${pass.name}] pos-bill-expanded ok`)

  await ctx.close()
}

await browser.close()
console.log('DONE — shots in', OUT)
