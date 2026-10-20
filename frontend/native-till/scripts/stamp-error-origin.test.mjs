import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { errorPageOrigin, readErrorOrigin, stampErrorOrigin } from './stamp-error-origin.mjs'

const errorHtml = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'www', 'error.html'), 'utf8')

test('thin client: the retry origin is server.url (scheme + host + port, no path)', () => {
  assert.equal(errorPageOrigin({ server: { url: 'https://app.native-app.my.id' } }), 'https://app.native-app.my.id')
  assert.equal(
    errorPageOrigin({ server: { url: 'https://a8.tailbf9662.ts.net:8443/' } }),
    'https://a8.tailbf9662.ts.net:8443',
  )
})

test('bundled shell (no server.url): the retry origin is the local WebView origin', () => {
  assert.equal(errorPageOrigin({ server: { errorPath: 'error.html' } }), 'https://localhost')
  assert.equal(errorPageOrigin({}), 'https://localhost')
  assert.equal(errorPageOrigin({ server: { hostname: 'app', androidScheme: 'http' } }), 'http://app')
})

test('stamping the real error page points its retry at the prod origin, not the UAT default', () => {
  const stamped = stampErrorOrigin(errorHtml, 'https://app.native-app.my.id')
  assert.equal(readErrorOrigin(stamped), 'https://app.native-app.my.id')
  assert.doesNotMatch(stamped, /tailbf9662/)
})

test('stamping is idempotent (cap copy runs on every sync)', () => {
  const once = stampErrorOrigin(errorHtml, 'https://app.native-app.my.id')
  assert.equal(stampErrorOrigin(once, 'https://app.native-app.my.id'), once)
})

test('the page prefers Capacitor\'s runtime origin, so an unstamped copy (OTA bundle) still retries home', () => {
  assert.match(errorHtml, /var ORIGIN = window\.WEBVIEW_SERVER_URL \|\| STAMPED_ORIGIN/)
})

test('a page without exactly one STAMPED_ORIGIN assignment fails loudly instead of shipping unstamped', () => {
  assert.throws(() => stampErrorOrigin("<script>var ORIGIN = 'https://a'</script>", 'https://a.example'), /exactly one/)
  assert.throws(
    () => stampErrorOrigin("var STAMPED_ORIGIN = 'https://a'\nvar STAMPED_ORIGIN = 'https://b'", 'https://a.example'),
    /exactly one/,
  )
  assert.throws(() => readErrorOrigin('<p>no origin</p>'), /exactly one/)
})

test('an origin that would break the JS string literal is refused', () => {
  assert.throws(() => stampErrorOrigin(errorHtml, "https://a.example'"), /origin/)
})
