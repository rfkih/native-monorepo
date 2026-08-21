// Build a signed, environment-specific Native Karyawan (Employee) artifact — APK (sideload) or AAB
// (Play). Mirrors native-till/scripts/build-app.mjs. (ADR 0058 / ADR 0049 P5)
//
// UAT and prod are SEPARATE installable apps (Gradle product flavors uat/prod → distinct
// applicationId + name + badged icon). This pairs the right WebView origin with the right flavor in
// ONE command: it sets NATIVE_EMPLOYEE_URL (read by capacitor.config.ts at sync time), runs
// `cap sync`, then `assemble<Flavor>Release` (APK) or `bundle<Flavor>Release` (AAB), and copies the
// artifact to dist/ with an environment-named filename.
//
// --format apk (default) → an installable/sideloadable .apk. --format aab → an Android App Bundle
// for Google Play upload (Play no longer accepts APKs for new apps). Both are signed by the release
// signingConfig when android/keystore.properties is present; without it the artifact is UNSIGNED.
// NOTE: this app has NO keystore.properties yet — generate its OWN upload key (NEVER reuse the Till
// app's key) before an AAB can be uploaded to Play. See the app README / RUNBOOK.
//
// Usage (PowerShell):
//   npm run build:uat                                   # → UAT employee origin default (APK)
//   npm run aab:prod                                    # → prod AAB for Play (needs --url or NATIVE_EMPLOYEE_URL)
//   $env:NATIVE_EMPLOYEE_URL="https://me.example.com"; npm run build:prod
//   node scripts/build-app.mjs --env prod --format aab --url https://karyawan.example.com
//
// Prod requires an EXPLICIT stable origin and refuses an ephemeral *.trycloudflare.com quick-tunnel
// URL unless --allow-ephemeral is passed (defer the prod build until the named tunnel / domain lands).

import { execSync } from 'node:child_process'
import { existsSync, mkdirSync, copyFileSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '..')
const androidDir = join(app, 'android')

// --- args ---------------------------------------------------------------------------------------
const argv = process.argv.slice(2)
const arg = (name) => {
  const i = argv.indexOf(name)
  return i >= 0 ? argv[i + 1] : undefined
}
const env = (arg('--env') ?? '').toLowerCase()
const type = (arg('--type') ?? 'release').toLowerCase()
// --format apk (default, sideload/UAT edge) | aab (Android App Bundle, the ONLY format the Play
// Store accepts for a new app upload). AAB is a publishing artifact — Play re-signs it with the app
// signing key (Play App Signing) using our release key as the UPLOAD key, so it is release-only.
const format = (arg('--format') ?? 'apk').toLowerCase()
const allowEphemeral = argv.includes('--allow-ephemeral')
if (env !== 'uat' && env !== 'prod') {
  console.error('build-app: --env must be "uat" or "prod"')
  process.exit(1)
}
if (type !== 'release' && type !== 'debug') {
  console.error('build-app: --type must be "release" or "debug"')
  process.exit(1)
}
if (format !== 'apk' && format !== 'aab') {
  console.error('build-app: --format must be "apk" or "aab"')
  process.exit(1)
}
if (format === 'aab' && type !== 'release') {
  console.error('build-app: --format aab is Play-only and must be release (drop --type debug)')
  process.exit(1)
}

// --- origin -------------------------------------------------------------------------------------
// The Employee app's OWN funnel origin (ADR 0049 P5) — the /me self-service surface, NOT :8443.
const UAT_DEFAULT = 'https://a8.tailbf9662.ts.net:10000'
let origin = arg('--url') ?? process.env.NATIVE_EMPLOYEE_URL
if (!origin) {
  if (env === 'uat') origin = UAT_DEFAULT
  else {
    console.error(
      'build-app: prod needs an explicit stable origin — pass --url https://<domain> or set\n' +
        '           NATIVE_EMPLOYEE_URL. No prod default on purpose (the origin is baked into the APK).',
    )
    process.exit(1)
  }
}
if (env === 'prod' && /trycloudflare\.com/i.test(origin) && !allowEphemeral) {
  console.error(
    `build-app: refusing to bake an ephemeral quick-tunnel URL into a PROD app:\n  ${origin}\n` +
      '           That URL changes on the next prod tunnel restart and the installed app would break.\n' +
      '           Wait for the named tunnel / domain, or pass --allow-ephemeral for a throwaway test build.',
  )
  process.exit(1)
}

// --- toolchain ----------------------------------------------------------------------------------
const buildEnv = { ...process.env, NATIVE_EMPLOYEE_URL: origin }
if (!buildEnv.JAVA_HOME) {
  const jbr = 'C:\\Program Files\\Android\\Android Studio\\jbr'
  if (process.platform === 'win32' && existsSync(jbr)) {
    buildEnv.JAVA_HOME = jbr
    console.log(`[build-app] JAVA_HOME unset → using Android Studio JBR: ${jbr}`)
  } else {
    console.warn('[build-app] JAVA_HOME is not set — set it to a JDK 21 (Android Studio JBR works).')
  }
}

const flavor = env === 'uat' ? 'Uat' : 'Prod'
const typeCap = type === 'release' ? 'Release' : 'Debug'
const gradlew = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew'
const run = (cmd, cwd) => {
  console.log(`\n[build-app] $ ${cmd}   (cwd: ${cwd})`)
  execSync(cmd, { cwd, env: buildEnv, stdio: 'inherit' })
}

const isAab = format === 'aab'
const gradleTask = isAab ? 'bundle' : 'assemble'
console.log(`[build-app] env=${env}  type=${type}  format=${format}  origin=${origin}`)
run('npx cap sync android', app)
run(`${gradlew} ${gradleTask}${flavor}${typeCap}`, androidDir)

// --- collect the artifact -----------------------------------------------------------------------
// APK: AGP names an UNSIGNED release `app-<flavor>-release-unsigned.apk`; a signed one drops the
// suffix, and outputs land under apk/<flavor>/<type>/.
// AAB: the release bundle is always `app-<flavor>-<type>.aab` (no unsigned suffix — an unsigned AAB
// keeps the same name), under bundle/<flavor><Type>/ (e.g. bundle/prodRelease/).
const signed = type === 'release' && existsSync(join(androidDir, 'keystore.properties'))
const outDir = isAab
  ? join(androidDir, 'app', 'build', 'outputs', 'bundle', `${env}${typeCap}`)
  : join(androidDir, 'app', 'build', 'outputs', 'apk', env, type)
const candidates = isAab
  ? [`app-${env}-${type}.aab`]
  : [`app-${env}-${type}.apk`, `app-${env}-${type}-unsigned.apk`]
const builtName = candidates.find((n) => existsSync(join(outDir, n)))
if (!builtName) {
  console.error(
    `[build-app] expected ${format.toUpperCase()} not found in ${outDir} (looked for: ${candidates.join(', ')})`,
  )
  process.exit(1)
}
const built = join(outDir, builtName)
const gradleText = readFileSync(join(androidDir, 'app', 'build.gradle'), 'utf8')
const versionCode = (gradleText.match(/versionCode\s+(\d+)/) ?? [])[1] ?? '0'
const suffix = signed ? '' : '-unsigned'
const distDir = join(app, 'dist')
mkdirSync(distDir, { recursive: true })
const out = join(distDir, `native-employee-app-${env}-v${versionCode}${suffix}.${format}`)
copyFileSync(built, out)

console.log(`\n[build-app] ✓ ${env} ${format.toUpperCase()}: ${out}`)
if (!signed) {
  console.warn(
    `[build-app] NOTE: UNSIGNED (no android/keystore.properties). ${
      isAab ? 'Play needs the release upload key — Play upload will reject it.' : 'Not installable as a release.'
    }`,
  )
}
console.log(
  isAab
    ? '[build-app] next: upload this .aab to Play Console → Production/Testing → Create release. ' +
        'Bump build.gradle versionCode before the NEXT upload (Play rejects a re-used versionCode).'
    : `[build-app] next: copy it into docker/${env}/downloads/ and bump the edge "latest" alias.`,
)
