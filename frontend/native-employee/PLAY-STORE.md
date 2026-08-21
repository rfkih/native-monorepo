# Google Play — Native Karyawan (Employee app) publishing kit

Everything needed to publish `id.co.nativeapp.employee` (launcher name **Native Karyawan**) to Google
Play. Sibling of the Business app's kit (`frontend/native-till/PLAY-STORE.md`) — the account,
Play App Signing, closed-testing gate, content-rating and target-audience answers are IDENTICAL, so
this doc does not repeat them; read the Business kit §1 + §6 for those. What's **different for the
Employee app** is called out here: its own origin, its own signing key, its own listing copy, and a
slimmer data-safety surface.

> Copy blocks are bilingual (EN default + `id-ID`). Replace every `[BRACKET]` placeholder before you
> submit. The privacy policy is SHARED with the Business app — one URL is fine for both listings.

---

## 0. Blockers to clear first (Employee-specific)

1. ~~A permanent HTTPS origin on port 443.~~ **✅ ALREADY DONE.** The durable employee origin is
   **`https://emp.native-app.my.id`** (Cloudflare named tunnel → `edge:8081`, ADR 0057). Verified
   2026-08-16: it returns HTTP 200 serving the employee `/me` SPA, and Keycloak's `native-console`
   client already whitelists `https://emp.native-app.my.id/*` as a redirect URI (the VPS
   `prod.env` has `EMPLOYEE_PUBLIC_URL=https://emp.native-app.my.id` and the origin-patch has run).
   Bake THIS origin into the app — NOT the funnel `ts.net:10000` (non-standard port, blocked on ID
   mobile) and NOT the Business `app.native-app.my.id` (that loads the full console, not `/me`).
2. **This app has NO signing key yet.** The Business app reuses `native-till`; the Employee app must
   have its **own** upload key — never reuse the Till key (a leaked key would compromise both). See §2.
   *(This is now the only remaining hard blocker.)*
3. **A reviewer test account** — the app is login-gated (see §5, App access).

Privacy policy is already handled: `https://app.native-app.my.id/privacy.html` (added at
`frontend/console/public/privacy.html`, shared with the Business app). Fill its placeholders and
deploy the console before you submit — Play verifies the URL loads.

---

## 1. Developer account

Same as the Business kit (§1): **US$25 one-time**, identity verification, and — because your account
is **Personal** — the **closed-testing gate**: ≥12 opted-in testers kept live for ≥14 continuous days
before you can apply for Production. If you register an **Organization** account (needs a D-U-N-S
number), that gate is waived. One developer account publishes BOTH apps — you don't pay twice.

## 2. Play App Signing — generate the Employee upload key (do NOT reuse Till's)

Opt in to **Play App Signing** (default). You upload with your **own upload key**; Google holds the
app signing key. Generate the Employee key once:

```powershell
# Pick a folder OUTSIDE the repo, e.g. C:\Users\rifki\native-employee-signing\
keytool -genkeypair -v `
  -keystore C:\Users\rifki\native-employee-signing\native-employee-upload.jks `
  -alias native-employee -keyalg RSA -keysize 2048 -validity 10000
```

Then create `frontend/native-employee/android/keystore.properties` (gitignored — NEVER commit):

```properties
storeFile=C:\\Users\\rifki\\native-employee-signing\\native-employee-upload.jks
storePassword=[STORE_PASSWORD]
keyAlias=native-employee
keyPassword=[KEY_PASSWORD]
```

- **Back that folder up in two places.** Lose the upload key and you can't publish updates.
- No Gradle change needed — `bundleProdRelease` picks it up via `keystore.properties` exactly like the
  Till app (`android/app/build.gradle` signingConfigs.release).

## 3. Build the upload artifact (AAB — Play requires it)

Tooling added (`scripts/build-app.mjs --format aab`, npm `aab:prod`):

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
- **versionCode** starts at `1` in `android/app/build.gradle`. It must **increase on every upload** —
  bump it (and `versionName`) before each new Production release.
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
- **Contact:** email `[EMAIL]` (required, public), website `https://native-app.my.id`
- **Privacy policy URL:** `https://app.native-app.my.id/privacy.html`

### Graphic assets
Generate the icon + feature graphic deterministically from the brand (no running app needed):

```powershell
cd frontend/native-employee
npm run store:assets
# → store-assets/icon-512.png          (512×512 app icon)
# → store-assets/feature-1024x500.jpg  (1024×500 feature graphic, opaque)
```

Screenshots (≥2, phone) must come from the running `/me` surface — open `https://emp.native-app.my.id` (or the
console `/me` with `VITE_AUTH_MODE=dev` for a seeded staff profile) and capture: Beranda (payslip +
leave stat cards), Slip gaji, and Cuti/Lembur. Min 320px on the short side, 16:9 or 9:16.

| Asset | Spec |
|---|---|
| App icon | 512×512 PNG (`npm run store:assets`) |
| Feature graphic | 1024×500 JPG, no alpha (`npm run store:assets`) |
| Phone screenshots | ≥2 (up to 8), from the `/me` surface |

## 5. Required forms (App content)

- **App access:** login-gated → provide a **reviewer demo employee account**. Create a low-privilege
  staff login on `https://emp.native-app.my.id` (a demo company employee) and enter username + password with a
  one-line "open the app → sign in with these" note. Without it Google rejects the app.
- **Privacy policy:** the shared URL above.
- **Data safety:** draft below (slimmer than the Business app — the employee only sees their OWN data).
- **Content rating:** questionnaire → expected **Everyone / PEGI 3**.
- **Target audience:** 18+ (employees) — not designed for children.
- **Ads:** **No ads**.

### Data safety — draft answers

**Collect user data?** Yes. **Share data?** No. **Sell data?** No.
**Encrypted in transit?** Yes. **Users can request deletion?** Yes, via their employer / `[EMAIL]`.

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

- [x] Durable 443 origin `https://emp.native-app.my.id` is live + Keycloak-whitelisted (**§0.1 — DONE**)
- [ ] Employee upload keystore generated + `android/keystore.properties` written (**§2**)
- [ ] `npm run aab:prod` produces a signed `dist/native-employee-app-prod-v1.aab` (**§3**)
- [ ] `npm run store:assets` → icon + feature graphic; screenshots captured (**§4**)
- [ ] `[EMAIL]` + reviewer demo employee account ready (**§5**)
- [ ] Listing copy pasted (EN + ID), Data safety + content rating filled (**§4–5**)
