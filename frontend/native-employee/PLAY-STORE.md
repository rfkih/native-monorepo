# Google Play — Native Karyawan (Employee app) publishing kit

Everything needed to publish `id.co.nativeapp.employee` (launcher name **Native Karyawan**) to Google
Play. Sibling of the Business app's kit (`frontend/native-till/PLAY-STORE.md`) — the account,
Play App Signing, closed-testing gate, content-rating and target-audience answers are IDENTICAL, so
this doc does not repeat them; read the Business kit §1 + §6 for those. What's **different for the
Employee app** is called out here: its own origin, its own signing key, its own listing copy, and a
slimmer data-safety surface.

> Copy blocks are bilingual (EN default + `id-ID`); every placeholder in this doc is already filled
> in. The privacy policy AND the account-deletion page are SHARED with the Business app — one URL
> each is fine for both listings.

> **Status (1 Sep 2026).** Signed AAB, icon, feature graphic and four framed screenshots are all
> built. Both remaining blockers below are cleared; what is left is account-side and human.

---

## 0. Blockers to clear first (Employee-specific)

1. ~~A permanent HTTPS origin on port 443.~~ **✅ ALREADY DONE.** The durable employee origin is
   **`https://emp.native-app.my.id`** (Cloudflare named tunnel → `edge:8081`, ADR 0057). Verified
   2026-08-16: it returns HTTP 200 serving the employee `/me` SPA, and Keycloak's `native-console`
   client already whitelists `https://emp.native-app.my.id/*` as a redirect URI (the VPS
   `prod.env` has `EMPLOYEE_PUBLIC_URL=https://emp.native-app.my.id` and the origin-patch has run).
   Bake THIS origin into the app — NOT the funnel `ts.net:10000` (non-standard port, blocked on ID
   mobile) and NOT the Business `app.native-app.my.id` (that loads the full console, not `/me`).
2. ~~This app has NO signing key yet.~~ **✅ DONE (31 Aug 2026).** Its own upload key is minted at
   `C:\Users\rifki\native-employee-signing\` (`native-employee-release.jks` + `CREDENTIALS.txt`),
   distinct from the Till key — cert `CN=Native Karyawan, O=Native, C=ID`, valid to 2054.
   **Back that folder up in two places**: it is the permanent identity of `id.co.nativeapp.employee`.
3. **A reviewer test account** — the app is login-gated (see §5, App access).

Legal pages are shared with the Business app and already written:
`https://app.native-app.my.id/privacy.html` and `https://app.native-app.my.id/delete-account.html`
(both from `frontend/console/public/`). Their placeholders are filled; **deploy the console before
you submit** so the deletion page actually resolves — see the Business kit §0.3 for why you must
verify by page title rather than HTTP status.

> Note the legal links inside this app point at the **business** origin on purpose. This app's own
> origin (`emp.native-app.my.id`) is a separate build that does not ship those static files, so a
> relative link would land a reviewer on the SPA shell instead of the policy. See
> `frontend/console/src/lib/config.ts` (`LEGAL_BASE_URL`).

---

## 1. Developer account

Same as the Business kit (§1): **US$25 one-time**, identity verification, and — because Native is
operated by an individual, so the account is **Personal** — the **closed-testing gate**: ≥12 opted-in
testers kept live for ≥14 continuous days before you can apply for Production. One developer account
publishes BOTH apps — you don't pay twice, and both listings sit behind the same gate. You can run
the two closed tests in parallel so the 14 days overlap.

## 2. Play App Signing — the Employee upload key (already minted; do NOT reuse Till's)

Opt in to **Play App Signing** (default). You upload with your **own upload key**; Google holds the
app signing key. **This is done** — the key exists at
`C:\Users\rifki\native-employee-signing\native-employee-release.jks` (alias in `CREDENTIALS.txt`)
and `frontend/native-employee/android/keystore.properties` (gitignored) already points at it, which
is why `dist/native-employee-app-prod-v3.aab` verifies. Recorded here only so it can be recreated:

```powershell
# Pick a folder OUTSIDE the repo, e.g. C:\Users\rifki\native-employee-signing\
keytool -genkeypair -v `
  -keystore C:\Users\rifki\native-employee-signing\native-employee-release.jks `
  -alias native-employee -keyalg RSA -keysize 2048 -validity 10000
```

- **Back that folder up in two places.** Lose the upload key and you can't publish updates — and
  unlike a rebuildable artifact, it cannot be regenerated: a new key is a different app to Play.
- Never reuse the Till key here; one leak would otherwise compromise both apps.
- No Gradle change needed — `bundleProdRelease` picks it up via `keystore.properties` exactly like
  the Till app (`android/app/build.gradle` signingConfigs.release).

## 3. Build the upload artifact (AAB — Play requires it)

**Already built — you do not need to run this for the first submission.** The upload artifact is
`dist/native-employee-app-prod-v3.aab` (versionCode 3 / versionName 1.2; *jar verified*, cert
`CN=Native Karyawan`, valid to 2054), baking `https://emp.native-app.my.id` with
`app.native-app.my.id` whitelisted for the login redirect. Rebuild only on a native change:

```powershell
cd frontend/native-employee
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:NATIVE_EMPLOYEE_URL = "https://emp.native-app.my.id"   # durable prod origin, baked in
npm run aab:prod
# → dist/native-employee-app-prod-v<versionCode>.aab   (signed with the Employee upload key)
```

- The prod build **refuses to run without an explicit `--url`/`NATIVE_EMPLOYEE_URL`** (no prod
  default on purpose) and refuses ephemeral `*.trycloudflare.com` URLs — so you can't accidentally
  bake a throwaway origin into a store build.
- The build also bakes the **auth host** into the shell's `allowNavigation` (prod default
  `https://app.native-app.my.id` — the Business origin serving Keycloak). Without it, the login
  redirect leaves the WebView and Android throws the user into Chrome ("app opens in the browser").
  Only override (`--auth-url` / `NATIVE_EMPLOYEE_AUTH_ORIGIN`) if `PUBLIC_URL` ever moves — and then
  rebuild + re-release this app.
- **versionCode** is `3` in `android/app/build.gradle`; the next upload is `4`. It must **increase
  on every upload** — bump it (and `versionName`) before each new Production release.
- Origin is baked at build time (thin client), so `/me` feature changes ship via the normal **web
  deploy** with **no** Play release. You only re-upload the AAB on **native** changes (plugin,
  manifest, SDK bump).

## 4. Store listing copy

### App title (max 30 chars)
```
Native Karyawan
```

### Short description (max 80 chars)

**EN**
```
Payslips, leave, claims & your sales — self-service for Native employees.
```
**ID**
```
Slip gaji, cuti, klaim & penjualanmu — layanan mandiri karyawan Native.
```

### Full description (max 4000 chars)

**ID**
```
Native Karyawan adalah aplikasi layanan mandiri untuk karyawan perusahaan yang memakai Native. Semua
kebutuhan kepegawaianmu ada dalam satu genggaman.

SLIP GAJI
• Lihat dan buka slip gaji setiap periode.
• Ringkasan penghasilan berjalan (year-to-date).

CUTI & LEMBUR
• Cek sisa saldo cuti dan ajukan cuti.
• Catat lembur untuk persetujuan.

KLAIM & REIMBURSEMENT
• Ajukan klaim pengeluaran dan pantau statusnya.

PENJUALAN & KOMISI (untuk peran tertentu)
• Lihat penjualanmu sendiri beserta estimasi komisi.

DATA PRIBADI
• NIK, rekening, NPWP, dan status PTKP ditampilkan tersamar (masked) dan aman — data sensitif
  dienkripsi dan tidak pernah dicatat di log.

Aplikasi ini untuk karyawan dari perusahaan yang sudah memakai Native, dan memerlukan akun karyawan
yang dibuat oleh perusahaanmu. Berjalan aman lewat koneksi terenkripsi.

Kebijakan Privasi: https://app.native-app.my.id/privacy.html
```

**EN**
```
Native Karyawan (Employee) is the self-service app for staff of businesses that run on Native.
Everything you need as an employee, in one place.

PAYSLIPS
• View and open your payslip each pay period.
• Year-to-date earnings summary.

LEAVE & OVERTIME
• Check your remaining leave balance and request time off.
• Log overtime for approval.

EXPENSE CLAIMS
• Submit expense claims and track their status.

SALES & COMMISSION (for eligible roles)
• See your own sales with an estimated commission.

PERSONAL DATA
• National ID (NIK), bank account, NPWP and PTKP status are shown masked and kept secure — sensitive
  data is encrypted and never logged.

This app is for employees of businesses already using Native and requires an employee account created
by your company. It runs securely over an encrypted connection.

Privacy Policy: https://app.native-app.my.id/privacy.html
```

### Categorization & contact
- **App category:** Business
- **Tags:** Employee self-service, Payroll, HR
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
| Phone screenshots | ≥2 (max 8), 9:16 | `store-assets/framed/01…04-*.png` — four 1080×1920 ✅ |

```powershell
cd frontend/native-employee
npm run store:assets   # icon + feature graphic, rendered from the brand (no running app needed)
npm run store:shots    # captions + phone frames over store-assets/screenshots/ → store-assets/framed/
npm run icons          # LAUNCHER icons → android/.../mipmap-<density>/ (native change: bump versionCode)
```

Upload the **framed** set, not the raw `store-assets/screenshots/` captures — the framing matches the
Business listing so the two apps read as one product family. Raw captures come from the running `/me`
surface (`https://emp.native-app.my.id`, or the console `/me` with `VITE_AUTH_MODE=dev` for a seeded
staff profile); re-capture them at 1080×1920 and re-run `store:shots` when the UI changes.

> **This app's glyph is a PERSON, not the shared trend mark** (v3). Both apps keep the same cyan
> brand tile, so they read as siblings, but they are tellable apart in the store and on a phone that
> has both installed — which is the normal case: the owner runs Native, their staff run Native
> Karyawan. The glyph is defined once per surface and the three must stay in step:
> `android/app/src/main/res/drawable/ic_launcher_fg_employee.xml` (adaptive icon),
> `scripts/render-launcher-icons.mjs` (API 24–25 rasters), and `scripts/render-store-assets.mjs`
> (the 512 store icon + the feature-graphic tile).

## 5. Required forms (App content)

- **App access:** login-gated → provide a **reviewer demo employee account**. Create a low-privilege
  staff login on `https://emp.native-app.my.id` (a demo company employee) and enter username +
  password with a one-line "open the app → sign in with these" note. Usernames are company-scoped
  (`<code>.<local>`) — spell the full string out. Without this Google rejects the app.
- **Privacy policy:** the shared URL above.
- **Data deletion:** give `https://app.native-app.my.id/delete-account.html`. This app does **not**
  offer account creation (employers create staff accounts), so the requirement is lighter here than
  for the Business app — but declare the URL anyway, and note that the page's §2 explains that
  employee accounts are closed via the employer. In-app route: **Akun saya → Privasi & penghapusan
  akun**.
- **Data safety:** draft below (slimmer than the Business app — the employee only sees their OWN data).
- **Content rating:** questionnaire → expected **Everyone / PEGI 3**.
- **Target audience:** 18+ (employees) — not designed for children.
- **Ads:** **No ads**.

### Data safety — draft answers

**Collect user data?** Yes. **Share data?** No. **Sell data?** No.
**Encrypted in transit?** Yes. **Users can request deletion?** Yes — tick *"Users can request that
their data be deleted"*, supply the deletion URL above (requests route via the employer or
`rfkih23@gmail.com`).

| Data type | Collected | Shared | Purpose | Notes |
|---|---|---|---|---|
| Name | Yes | No | Account management, App functionality | own profile |
| User IDs | Yes | No | Account management | employee login / auth |
| Financial info (salary, payslip, own commission) | Yes | No | App functionality | employer-provided, shown to the employee, encrypted |
| Financial info (bank account) | Yes | No | App functionality | masked, encrypted |
| Other personal info (NIK / NPWP / PTKP) | Yes | No | App functionality | masked, encrypted |
| App activity / diagnostics (logs, crash) | Yes | No | Security, Diagnostics | |

> No **location**, no **contacts**, no **photos** collected by this app (it's read-mostly
> self-service). "Shared = No" throughout — the employee's own data is never handed to a third party
> for their own purposes.

## 6. Release flow

Identical to the Business kit §6: **Internal testing** → (Personal account) **Closed testing** ≥12
testers ×14 days → **Production**. Later native updates: bump `versionCode`, `npm run aab:prod`,
upload a new release. You can run the Employee app's tracks in parallel with the Business app's under
the same developer account.

---

### Employee-app readiness checklist

Ready in the repo — nothing left to build:

- [x] Durable 443 origin `https://emp.native-app.my.id` live + Keycloak-whitelisted (**§0.1**)
- [x] Employee upload keystore minted + `android/keystore.properties` written (**§2**)
- [x] Signed `dist/native-employee-app-prod-v3.aab` — versionCode 3, *jar verified*, targetSdk 36 (**§3**)
- [x] Own person-glyph launcher + store icon, feature graphic, four framed 1080×1920 shots (**§4**)
- [x] Contact email `rfkih23@gmail.com`; privacy + deletion pages written and linked in-app (**§5**)
- [x] Listing copy drafted EN + ID; data-safety and content-rating answers drafted (**§4–5**)

Yours to do, in order:

- [ ] Play developer account registered (shared with the Business app — do that one first)
- [ ] Console deployed so `/privacy.html` and `/delete-account.html` resolve
- [ ] Reviewer demo **employee** login created on prod; exact username + password noted
- [ ] Upload the AAB → Internal testing → smoke-test sign-in, a payslip, a leave request
- [ ] Closed testing ≥12 testers × ≥14 days (can run in parallel with the Business app's)
- [ ] Production release submitted
