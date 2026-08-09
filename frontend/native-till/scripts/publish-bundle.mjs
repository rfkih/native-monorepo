// Publish an OTA web bundle for the Native Till shell (ADR 0051 P3, Capgo self-hosted).
//
// Produces, from the staged www/ (run `npm run bundle` first):
//   <out>/native-till-<version>.zip   the web bundle Capgo downloads and swaps in
//   <out>/updates.json                the endpoint payload: { version, url, checksum }
//
// The checksum is the SHA-256 (hex) of the zip — EXACTLY what the plugin recomputes and compares on
// download (verified against @capgo/capacitor-updater CryptoCipher.calcChecksum / DownloadService).
// We roll our own zip (not `@capgo/cli bundle zip`) on purpose: the CLI parses capacitor.config.ts,
// which the native-till `typescript@^7` devDep breaks ("Cannot read properties of undefined (reading
// 'CommonJS')"). A plain zip of the bundle's ROOT files (index.html at top level) is what Capgo
// unzips.
//
// SECURITY (ADR 0051 P3): a bare SHA-256 proves INTEGRITY (no corruption in transit) but NOT
// AUTHENTICITY — anyone who can serve/replace this endpoint or the zip can push arbitrary web code to
// every till. Before OTA is switched on in production, enable Capgo encryption v2 (an RSA-signed
// checksum: the app is built with the public key, only the holder of the private key can mint a valid
// bundle). This script is the integrity-only baseline for UAT/device bring-up.
//
// Usage (PowerShell):
//   $env:NATIVE_TILL_BUNDLE_VERSION = "1.0.1"   # semver; Capgo compares it to the running bundle
//   $env:NATIVE_TILL_OTA_BASE_URL   = "https://<origin>/app/updates"
//   node scripts/publish-bundle.mjs

import archiver from 'archiver'
import { createHash } from 'node:crypto'
import { createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const nativeTill = resolve(here, '..')
const www = join(nativeTill, 'www')
const outDir = resolve(process.env.NATIVE_TILL_OTA_OUT ?? join(nativeTill, 'ota-dist'))

const version = process.env.NATIVE_TILL_BUNDLE_VERSION
const baseUrl = process.env.NATIVE_TILL_OTA_BASE_URL

if (!version) throw new Error('NATIVE_TILL_BUNDLE_VERSION is required (a semver, e.g. 1.0.1)')
if (!baseUrl) throw new Error('NATIVE_TILL_OTA_BASE_URL is required (where the zip is served)')
if (!existsSync(join(www, 'index.html'))) {
  throw new Error(`www/index.html missing — run \`npm run bundle\` first to stage the bundle`)
}

mkdirSync(outDir, { recursive: true })
const zipName = `native-till-${version}.zip`
const zipPath = join(outDir, zipName)

console.log(`[publish] zipping ${www} -> ${zipPath}`)
await new Promise((resolvePromise, reject) => {
  const output = createWriteStream(zipPath)
  const archive = archiver('zip', { zlib: { level: 9 } })
  output.on('close', resolvePromise)
  archive.on('error', reject)
  archive.pipe(output)
  // Bundle ROOT = the www contents (index.html at the top level), NOT a www/ wrapper folder — that
  // is what Capgo expects to unzip. error.html is harmless extra baggage; it is left in.
  archive.directory(www, false)
  void archive.finalize()
})

// SHA-256 (hex) of the zip — the value the plugin recomputes and compares (calcChecksum).
const checksum = await new Promise((resolvePromise, reject) => {
  const hash = createHash('sha256')
  createReadStream(zipPath)
    .on('data', (chunk) => hash.update(chunk))
    .on('end', () => resolvePromise(hash.digest('hex')))
    .on('error', reject)
})

const manifest = { version, url: `${baseUrl.replace(/\/$/, '')}/${zipName}`, checksum }
writeFileSync(join(outDir, 'updates.json'), JSON.stringify(manifest, null, 2) + '\n')

const sizeMb = (readFileSync(zipPath).length / (1024 * 1024)).toFixed(2)
console.log(`[publish] ${zipName} (${sizeMb} MB)  sha256=${checksum}`)
console.log(`[publish] wrote ${join(outDir, 'updates.json')}:`)
console.log(JSON.stringify(manifest, null, 2))
console.log('[publish] serve both files under NATIVE_TILL_OTA_BASE_URL (see docker/uat/edge.conf).')
