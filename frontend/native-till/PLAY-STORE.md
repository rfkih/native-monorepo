# Google Play — Native (Business app) publishing kit

Everything needed to publish `id.co.nativeapp.till` (launcher name **Native**) to Google Play.
Companion to `README.md` (build) — this doc is the **store side**: account, artifact, listing copy,
and the forms Google requires. Sibling app `native-employee` gets its own listing later (it still
needs a stable 443 origin first).

> Copy blocks are bilingual. Play lets you add EN (default) + Indonesian (`id-ID`) translations of the
> listing — paste each language into its tab. Replace every `[BRACKET]` placeholder before submitting.

---

## 0. Blockers to clear first (do these before you build)

1. **Prod origin baked into the app must be the permanent 443 domain**, not a Tailscale funnel.
   Use `https://app.native-app.my.id` (Cloudflare, port 443). The funnel URL (`*.ts.net:8443`) can go
   offline and its non-standard port is blocked on Indonesian mobile networks — an installed Play app
   pointing there would break.
2. **Privacy policy must be live** at a public URL: `https://app.native-app.my.id/privacy.html`
   (file added at `frontend/console/public/privacy.html`). Fill its `[OPERATOR]/[EMAIL]/[ADDRESS]`
   placeholders and deploy the console before you submit — Play verifies the URL loads.
3. **A reviewer test account** (see §5, App access) — the app is login-gated; without demo
   credentials Google rejects it.

---

## 1. Developer account

- **Register:** https://play.google.com/console — **US$25 one-time**.
- **Personal vs Organization** (this changes your timeline):
  - **Organization** account: needs a **D-U-N-S number** (free, ~1–2 weeks from Dun & Bradstreet).
    **Exempt** from the closed-testing gate below.
  - **Personal** account created after 13 Nov 2023: **must run closed testing with ≥12 testers,
    opted-in for ≥14 continuous days**, before you can apply for Production access.
- Because this is a real business, an **Organization** account is the better fit (avoids the 12-tester
  gate and looks legitimate on the listing). Budget for the D-U-N-S wait.
- You'll also complete account-level: identity verification, a **contact email/phone**, and the
  **Payments profile** (only needed if you ever charge on Play — not required for a free app).

## 2. Play App Signing (do NOT skip)

- Opt in to **Play App Signing** (default for new apps). Google holds the **app signing key**; you
  sign uploads with your **upload key** = your existing keystore at
  `C:\Users\rifki\native-till-signing\`.
- **Back that folder up in two places.** Lose the upload key and you can't publish updates.
- No code change needed — `assembleProdRelease`/`bundleProdRelease` already sign with it via
  `android/keystore.properties`.

## 3. Build the upload artifact (AAB — Play requires it)

Play only accepts **`.aab`** for a new app (not `.apk`). Tooling now supports it:

```powershell
cd frontend/native-till
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:NATIVE_TILL_URL = "https://app.native-app.my.id"   # permanent prod origin, baked in
npm run aab:prod
# → dist/native-app-prod-v<versionCode>.aab   (signed with the upload key)
```

Upload that `.aab` to Play Console → **Testing** (internal → closed) → then **Production**.

- **versionCode** must increase on **every** upload. It's currently `8` in `android/app/build.gradle`.
  Bump it (and `versionName`) before each new upload — Play rejects a re-used versionCode.
- The origin is baked at build time (thin client), so most updates ship via the normal **web deploy**
  with **no** Play release. You only re-upload the AAB when **native** code changes (plugin, manifest,
  SDK bump) — see the README "Versioning contract".

## 4. Store listing copy

### App title (max 30 chars)
```
Native: Kasir & Keuangan
```

### Short description (max 80 chars)

**EN**
```
POS, accounting & payroll in one app for Indonesian small businesses.
```
**ID**
```
Aplikasi kasir, akuntansi & penggajian dalam satu genggaman untuk UMKM.
```

### Full description (max 4000 chars)

**ID**
```
Native adalah aplikasi kasir (POS), akuntansi, dan HR dalam satu tempat untuk pelaku usaha di
Indonesia — dari kedai kopi, restoran, hingga bisnis multi-cabang.

KASIR (POS)
• Catat penjualan cepat, kelola menu & varian, dan diskon.
• Cetak struk ke printer termal Bluetooth atau USB langsung dari perangkat.
• Terima pembayaran tunai maupun QRIS.
• Tutup kasir harian dengan rekap selisih kas otomatis.

KEUANGAN & AKUNTANSI
• Laporan Laba-Rugi, Neraca, dan Arus Kas yang otomatis dari transaksi.
• Utang & piutang, pajak (PPN/PB1), serta anggaran.
• Semua nilai uang akurat dalam Rupiah, tanpa pembulatan yang keliru.

KARYAWAN & PENGGAJIAN (HR)
• Data karyawan, absensi, cuti, dan penggajian sesuai aturan Indonesia.
• Data sensitif (gaji, NIK, rekening) dienkripsi dan aman.

MULTI-CABANG & MULTI-PERAN
• Kelola beberapa outlet dalam satu akun.
• Peran terpisah untuk pemilik, manajer, dan kasir.

Native berjalan aman lewat koneksi terenkripsi. Untuk memakai aplikasi ini Anda perlu akun Native.

Kebijakan Privasi: https://app.native-app.my.id/privacy.html
```

**EN**
```
Native is an all-in-one Point of Sale (POS), accounting, and HR app for businesses in Indonesia —
from a coffee stall to a restaurant to a multi-branch operation.

POINT OF SALE
• Fast sales, menu & variant management, and discounts.
• Print receipts to a Bluetooth or USB thermal printer straight from the device.
• Accept cash and QRIS payments.
• Daily register close with automatic cash-variance reconciliation.

FINANCE & ACCOUNTING
• Profit & Loss, Balance Sheet, and Cash Flow generated automatically from transactions.
• Payables & receivables, tax (VAT/PB1), and budgets.
• Money is always accurate in Rupiah — no float rounding errors.

EMPLOYEES & PAYROLL (HR)
• Employee records, attendance, leave, and Indonesia-compliant payroll.
• Sensitive data (salary, national ID, bank account) is encrypted and secure.

MULTI-BRANCH & MULTI-ROLE
• Manage several outlets under one account.
• Separate roles for owner, manager, and cashier.

Native runs securely over an encrypted connection. A Native account is required to use the app.

Privacy Policy: https://app.native-app.my.id/privacy.html
```

### Categorization & contact
- **App category:** Business
- **Tags:** Point of Sale, Accounting, Finance
- **Contact:** email `[EMAIL]` (required, public), website `https://native-app.my.id`
- **Privacy policy URL:** `https://app.native-app.my.id/privacy.html`

### Graphic assets (you must create these)
| Asset | Spec |
|---|---|
| App icon | 512×512 PNG, 32-bit |
| Feature graphic | 1024×500 PNG/JPG (no alpha) |
| Phone screenshots | ≥2 (up to 8), 16:9 or 9:16, min 320px side |
| (optional) 7"/10" tablet shots | if you declare tablet support |

> You already have a brand icon in the app (`android/app/src/main/res/mipmap-*`) and an OG image
> (`frontend/console/public/og-image.png`) to reuse as a base for the feature graphic. Take phone
> screenshots from the running app (POS, dashboard, a receipt) — the memory note
> "mobile-shots need VITE_AUTH_MODE=dev" is the recipe.

## 5. Required forms (App content)

- **App access:** the app requires login → provide a **reviewer demo account**.
  Create a low-privilege Native login on prod (e.g. a demo company) and enter username + password in
  the "All or some functionality is restricted" section with a one-line how-to-log-in note.
- **Privacy policy:** the URL above.
- **Data safety** (declare truthfully — draft below).
- **Content rating:** complete the questionnaire → expected rating **Everyone / PEGI 3** (a business
  tool, no objectionable content).
- **Target audience:** 18+ (business users) — **not** designed for children.
- **Ads:** declare **No ads**.
- **Government/financial-features declaration:** if asked, this is business bookkeeping/POS software,
  not a consumer banking, lending, or crypto product.

### Data safety — draft answers

**Does your app collect or share user data?** Yes (collect). **Sell data?** No.
**Is data encrypted in transit?** Yes. **Can users request deletion?** Yes (via `[EMAIL]`).

| Data type | Collected | Shared | Purpose | Notes |
|---|---|---|---|---|
| Name | Yes | No | Account management, App functionality | |
| Email address | Yes | No | Account management | |
| Phone number | Yes | No | Account management, Support | |
| User IDs | Yes | No | Account management | username / auth |
| Financial info (transactions, payment history) | Yes | No | App functionality | POS/accounting records |
| Financial info (bank account, salary) | Yes | No | App functionality | employer-entered, encrypted |
| Other personal info (national ID / NIK) | Yes | No | App functionality | encrypted, employer-entered |
| Photos (receipt attachments) | Yes | No | App functionality | only if user attaches |
| App activity / diagnostics (logs, crash) | Yes | No | Security, Diagnostics | |

> "Shared" = No because payment processing via a licensed gateway (QRIS/Midtrans) is a processing
> relationship, not sharing for the third party's own purposes. Confirm this classification for your
> exact integration before submitting.

## 6. Release flow

1. **Internal testing** track → upload the AAB → add yourself → install via the Play link, smoke-test
   login + a sale + a print.
2. (Personal account only) **Closed testing** → ≥12 testers, keep the track live ≥14 days.
3. **Production** → create release, fill "What's new", submit for review (first review can take days).
4. Later native updates: bump `versionCode`, `npm run aab:prod`, upload a new Production release.
```
