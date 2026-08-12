// Build a signed, environment-specific Native Till APK (ADR 0058).
//
// UAT and prod are SEPARATE installable apps (Gradle product flavors uat/prod → distinct
// applicationId + launcher name + badged icon). This script pairs the right WebView origin with the
// right flavor in ONE command, so a "UAT" app can never be built accidentally pointing at prod (or
// vice-versa): it sets NATIVE_TILL_URL (read by capacitor.config.ts at sync time), runs `cap sync`,
// then `assemble<Flavor>Release`, and copies the APK to dist/ with an environment-named filename.
//
// Usage (PowerShell):
//   npm run build:uat                             # → UAT origin default, Native UAT app
//   $env:NATIVE_TILL_URL="https://pos.example.com"; npm run build:prod
//   node scripts/build-app.mjs --env prod --url https://pos.example.com
//
// Prod requires an EXPLICIT stable origin and refuses an ephemeral *.trycloudflare.com quick-tunnel
// URL (which changes on every prod restart) unless --allow-ephemeral is passed. That is the guard
// behind "defer the prod build until the named tunnel / domain lands".

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
const allowEphemeral = argv.includes('--allow-ephemeral')
if (env !== 'uat' && env !== 'prod') {
  console.error('build-app: --env must be "uat" or "prod"')
  process.exit(1)
}
if (type !== 'release' && type !== 'debug') {
  console.error('build-app: --type must be "release" or "debug"')
  process.exit(1)
}

// --- origin -------------------------------------------------------------------------------------
const UAT_DEFAULT = 'https://a8.tailbf9662.ts.net:8443'
let origin = arg('--url') ?? process.env.NATIVE_TILL_URL
if (!origin) {
  if (env === 'uat') origin = UAT_DEFAULT
  else {
    console.error(
      'build-app: prod needs an explicit stable origin — pass --url https://<domain> or set\n' +
        '           NATIVE_TILL_URL. There is no prod default on purpose (the origin is baked into\n' +
        '           the APK and must be the permanent one, not an ephemeral quick-tunnel URL).',
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

// --- toolchain (match README: Android Studio JBR, NOT the backend JDK 25) ------------------------
const buildEnv = { ...process.env, NATIVE_TILL_URL: origin }
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

console.log(`[build-app] env=${env}  type=${type}  origin=${origin}`)
run('npx cap sync android', app)
run(`${gradlew} assemble${flavor}${typeCap}`, androidDir)

// --- collect the APK ----------------------------------------------------------------------------
// AGP names an UNSIGNED release `app-<flavor>-release-unsigned.apk`; a signed one drops the suffix.
const signed = type === 'release' && existsSync(join(androidDir, 'keystore.properties'))
const outDir = join(androidDir, 'app', 'build', 'outputs', 'apk', env, type)
const candidates = [`app-${env}-${type}.apk`, `app-${env}-${type}-unsigned.apk`]
const builtName = candidates.find((n) => existsSync(join(outDir, n)))
if (!builtName) {
  console.error(`[build-app] expected APK not found in ${outDir} (looked for: ${candidates.join(', ')})`)
  process.exit(1)
}
const built = join(outDir, builtName)
const gradleText = readFileSync(join(androidDir, 'app', 'build.gradle'), 'utf8')
const versionCode = (gradleText.match(/versionCode\s+(\d+)/) ?? [])[1] ?? '0'
const suffix = signed ? '' : '-unsigned'
const distDir = join(app, 'dist')
mkdirSync(distDir, { recursive: true })
const out = join(distDir, `native-app-${env}-v${versionCode}${suffix}.apk`)
copyFileSync(built, out)

console.log(`\n[build-app] ✓ ${env} APK: ${out}`)
if (!signed) {
  console.warn('[build-app] NOTE: UNSIGNED (no android/keystore.properties). Not installable as a release.')
}
console.log(`[build-app] next: copy it into docker/${env}/downloads/ and bump the edge "latest" alias.`)
