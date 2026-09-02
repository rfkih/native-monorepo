# Google Play — Native (Business app) publishing kit

Everything needed to publish `id.co.nativeapp.till` (launcher name **Native**) to Google Play.
Companion to `README.md` (build) — this doc is the **store side**: account, artifact, listing copy,
and the forms Google requires. Sibling app `native-employee` has its own kit at
`frontend/native-employee/PLAY-STORE.md`; both ship under ONE developer account.

> **Status (1 Sep 2026).** The signed upload artifact, all graphics, and every form answer below are
> ready. What remains is account-side and human: register the Play account, deploy the console so
> the two legal URLs go live, create the reviewer login, and upload. See the checklist at the end.

> Copy blocks are bilingual. Play lets you add EN (default) + Indonesian (`id-ID`) translations of the
> listing — paste each language into its tab. Every placeholder in this doc is already filled in.

---

## 0. Blockers to clear first (do these before you build)

1. ~~Prod origin baked into the app must be the permanent 443 domain.~~ **DONE** — the shipped
   `v10` artifact bakes `https://app.native-app.my.id` (verified in `assets/capacitor.config.json`).
   Never ship a funnel URL (`*.ts.net:8443`): it can go offline and its non-standard port is blocked
   on Indonesian mobile networks, so an installed Play app pointing there would break.
2. ~~Fill the privacy policy's `[OPERATOR]/[EMAIL]/[ADDRESS]` placeholders.~~ **DONE** —
   `frontend/console/public/privacy.html` now names the operator and `rfkih23@gmail.com`. Native is
   operated by an **individual (perorangan)**, so there is deliberately no business address.
3. **Deploy the console** so BOTH legal URLs are live before you submit — Play fetches them:
   - `https://app.native-app.my.id/privacy.html` — live, but still serving the *pre-fill* version
   - `https://app.native-app.my.id/delete-account.html` — **new file, not deployed yet**

   The origin has an SPA fallback, so an undeployed path still returns **200** with the app shell.
   Verify by content (`curl -s <url> | grep '<title>'`), never by status code.
4. **A reviewer test account** (see §5, App access) — the app is login-gated; without demo
   credentials Google rejects it.

---

## 1. Developer account

- **Register:** https://play.google.com/console — **US$25 one-time**.
- **Account type: Personal.** Native is operated by an individual, so this is a personal account,
  and the **closed-testing gate applies**: ≥12 testers, opted in and kept live for **≥14 continuous
  days**, before you may apply for Production access. Budget for that fortnight — it is the longest
  pole in the whole submission.
  - (An **Organization** account waives the gate but needs a **D-U-N-S number** — free, ~1–2 weeks
    from Dun & Bradstreet — and a registered legal entity. Only worth it if you incorporate.)
- **The verified name Google publishes on the listing must match the operator named in
  `privacy.html` and `delete-account.html`.** A mismatch reads as misrepresentation. If the legal
  name on your ID differs from what those pages say, change the pages (both files, at the
  `<!--OPERATOR-->` markers) — not the account.
- You'll also complete account-level: identity verification, a **contact email/phone**
  (`rfkih23@gmail.com`), and the **Payments profile** (only needed if you ever charge on Play — not
  required for a free app).

## 2. Play App Signing (do NOT skip)

- Opt in to **Play App Signing** (default for new apps). Google holds the **app signing key**; you
  sign uploads with your **upload key** = your existing keystore at
  `C:\Users\rifki\native-till-signing\`.
- **Back that folder up in two places.** Lose the upload key and you can't publish updates.
- No code change needed — `assembleProdRelease`/`bundleProdRelease` already sign with it via
  `android/keystore.properties`.

## 3. Build the upload artifact (AAB — Play requires it)

Play only accepts **`.aab`** for a new app (not `.apk`). Tooling now supports it:

**Already built — you do not need to run this for the first submission.** The upload artifact is
`dist/native-app-prod-v11.aab` (versionCode 11 / versionName 1.9; `jarsigner -verify` reports *jar
verified*, cert `CN=Native Till, O=Native, C=ID`, valid to 2053). Rebuild only on a native change:

```powershell
cd frontend/native-till
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:NATIVE_TILL_URL = "https://app.native-app.my.id"   # permanent prod origin, baked in
npm run aab:prod
# → dist/native-app-prod-v<versionCode>.aab   (signed with the upload key)
```

Upload that `.aab` to Play Console → **Testing** (internal → closed) → then **Production**.

- **versionCode** must increase on **every** upload. It is `11` in `android/app/build.gradle`; the
  next upload is `12`. Play rejects a re-used versionCode.
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
- **Contact:** email `rfkih23@gmail.com` (required, public), website
  `https://app.native-app.my.id` — **the apex `native-app.my.id` has no DNS record**, so never put
  the bare domain in the listing; Play checks that the website URL resolves.
- **Privacy policy URL:** `https://app.native-app.my.id/privacy.html`
- **Data deletion URL:** `https://app.native-app.my.id/delete-account.html`

### Graphic assets — all rendered, verified against spec

| Asset | Spec | File |
|---|---|---|
| App icon | 512×512 PNG | `store-assets/icon-512.png` — 512×512 ✅ |
| Feature graphic | 1024×500 JPG, no alpha | `store-assets/feature-1024x500.jpg` — 1024×500 RGB ✅ |
| Phone screenshots | ≥2 (max 8), 9:16, min 320px side | `store-assets/framed/01…06-*.png` — six 1080×1920 ✅ |
| 7"/10" tablet shots | only if you declare tablet support | not produced — leave tablet support undeclared |

Regenerate with `npm run store:assets` (icon + feature graphic) and `node
scripts/frame-store-shots.mjs` (captioned phone frames from `screenshotproof/`).

The **launcher** icon is separate from the store icon: `npm run icons`
(`scripts/render-launcher-icons.mjs`) rewrites `android/.../mipmap-<density>/` from the same brand
gradient + glyph. Those rasters matter because `mipmap-anydpi-v26` — the adaptive icon — is only read
on API 26+, and `minSdkVersion` is 24; until v11 the API 24–25 fallback still shipped Capacitor's
stock blue logo.

> Shot 04 carries the app's own **"Angka ilustratif"** badge. Leave it — it is honest labelling of
> seeded figures, which is precisely what Play's misleading-screenshot rule wants to see.

## 5. Required forms (App content)

- **App access:** the app requires login → provide a **reviewer demo account**.
  Create a low-privilege Native login on prod (e.g. a demo company) and enter username + password in
  the "All or some functionality is restricted" section with a one-line how-to-log-in note.
  Usernames are company-scoped (`<code>.<local>`) — spell the full string out for the reviewer.
- **Privacy policy:** the URL above.
- **Data deletion (REQUIRED):** Play demands this of any app that lets users create an account, and
  Native does — `/signup` is public inside the WebView. Give
  `https://app.native-app.my.id/delete-account.html` as the deletion URL. The in-app route to the
  same page is **Akun saya → Privasi & penghapusan akun** (`/me/account`), which is what a reviewer
  looks for.
- **Data safety** (declare truthfully — draft below).
- **Content rating:** complete the questionnaire → expected rating **Everyone / PEGI 3** (a business
  tool, no objectionable content).
- **Target audience:** 18+ (business users) — **not** designed for children.
- **Ads:** declare **No ads**.
- **Government/financial-features declaration:** if asked, this is business bookkeeping/POS software,
  not a consumer banking, lending, or crypto product.

### Data safety — draft answers

**Does your app collect or share user data?** Yes (collect). **Sell data?** No.
**Is data encrypted in transit?** Yes. **Can users request deletion?** Yes — tick *"Users can request
that their data be deleted"* and supply the deletion URL above.

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

---

## 7. Business-app readiness checklist

Ready in the repo — nothing left to build:

- [x] Signed AAB `dist/native-app-prod-v11.aab` — versionCode 11, *jar verified*, targetSdk 36
- [x] Prod origin `https://app.native-app.my.id` baked in, plus the `allowNavigation` auth host
- [x] Icon, feature graphic, six framed phone screenshots
- [x] Privacy policy filled; account-deletion page written; both linked in-app (`/me/account`)
- [x] Listing copy EN + ID, data-safety and content-rating answers drafted above

Yours to do, in order:

- [ ] Register the Play developer account (US$25) + identity verification
- [ ] Deploy the console so `/privacy.html` (filled) and `/delete-account.html` are live —
      **verify by page title, not HTTP status** (the SPA fallback returns 200 for missing files)
- [ ] Create the reviewer demo login on prod; note the exact username + password
- [ ] Upload the AAB → Internal testing → smoke-test login, a sale, a receipt print
- [ ] Closed testing: ≥12 testers, ≥14 continuous days (personal-account gate)
- [ ] Apply for Production access → create the release → submit
```
