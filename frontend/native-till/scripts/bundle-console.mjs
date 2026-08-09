// Bundle the console into the Native Till shell (ADR 0051, P1).
//
// Builds frontend/console with ABSOLUTE api/keycloak URLs (a bundled app has no container-generated
// env.js — see console/src/lib/config.ts) and stages the output into native-till/www, which the APK
// packages and Capacitor serves from local disk (https://localhost) when NATIVE_TILL_BUNDLED=1.
//
// Usage (PowerShell):
//   $env:NATIVE_TILL_ORIGIN = "https://<your-origin>"; node scripts/bundle-console.mjs
// then:  $env:NATIVE_TILL_BUNDLED = "1"; npx cap sync android; (cd android; .\gradlew.bat assembleRelease)
//
// ORIGIN is the single deployed origin (single-origin UAT layout, docker/uat/edge.conf): the API is
// at ORIGIN/api and Keycloak at ORIGIN/auth. Defaults to the thin client's current SERVER_URL so a
// bare run is a working (UAT) bundle.

import { execSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, rmSync, cpSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const nativeTill = resolve(here, '..')
const consoleDir = resolve(nativeTill, '..', 'console')
const consoleDist = join(consoleDir, 'dist')
const www = join(nativeTill, 'www')

const ORIGIN = process.env.NATIVE_TILL_ORIGIN ?? 'https://a8.tailbf9662.ts.net:8443'
const REALM = process.env.NATIVE_TILL_REALM ?? 'native'

// The console reads these at build time (import.meta.env.VITE_*) — see console/src/lib/config.ts.
// API_BASE_URL is prefixed onto '/api/...' paths, so it is the bare ORIGIN (no /api). KEYCLOAK_URL
// has the realm appended in the auth layer, and the edge routes /auth/ to Keycloak — so ORIGIN/auth.
const buildEnv = {
  ...process.env,
  VITE_AUTH_MODE: 'oidc',
  VITE_API_BASE_URL: ORIGIN,
  VITE_KEYCLOAK_URL: `${ORIGIN}/auth`,
  VITE_KEYCLOAK_REALM: REALM,
}

console.log(`[bundle] console origin: ${ORIGIN} (realm ${REALM})`)
console.log('[bundle] building console…')
execSync('npm run build', { cwd: consoleDir, env: buildEnv, stdio: 'inherit' })

if (!existsSync(consoleDist)) {
  throw new Error(`console build produced no dist at ${consoleDist}`)
}

// Clear the previous bundle from www but KEEP the tracked offline shell (error.html) and .gitignore.
const keep = new Set(['error.html', '.gitignore'])
mkdirSync(www, { recursive: true })
for (const entry of readdirSync(www)) {
  if (!keep.has(entry)) rmSync(join(www, entry), { recursive: true, force: true })
}

// Stage the fresh build. error.html deliberately shadows any console-built error page (Capacitor's
// server.errorPath points at it) — the console dist has none, so there is no collision today.
console.log(`[bundle] staging dist -> ${www}`)
for (const entry of readdirSync(consoleDist)) {
  if (keep.has(entry)) continue
  cpSync(join(consoleDist, entry), join(www, entry), { recursive: true })
}

console.log('[bundle] done. Next: NATIVE_TILL_BUNDLED=1 npx cap sync android && gradlew assembleRelease')
