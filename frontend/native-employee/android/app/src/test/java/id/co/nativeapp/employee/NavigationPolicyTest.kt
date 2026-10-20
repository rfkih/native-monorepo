package id.co.nativeapp.employee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {

    private val ownDomains = listOf("native-app.my.id", "tailbf9662.ts.net")

    /** One shell build: its server.url scheme/host + its allowNavigation (as capacitor.config.ts makes it). */
    private class Shell(val scheme: String, val host: String, val allowed: Set<String>)

    private val prod = Shell("https", "emp.native-app.my.id", setOf("app.native-app.my.id"))
    // UAT: the employee origin (:10000) and Keycloak (:8443) share one host — ports are ignored.
    private val uat = Shell("https", "a8.tailbf9662.ts.net", setOf("a8.tailbf9662.ts.net", "app.native-app.my.id"))

    private fun Shell.blocks(url: String): Boolean {
        val uri = java.net.URI(url)
        return NavigationPolicy.blocks(uri.scheme, uri.host, scheme, host, { it in allowed }, ownDomains)
    }

    @Test
    fun `the field bug - a prod app retrying to the UAT funnel never reaches the browser`() {
        assertTrue(prod.blocks("https://a8.tailbf9662.ts.net:8443"))
        assertTrue(prod.blocks("https://native-prod.tailbf9662.ts.net/"))
    }

    @Test
    fun `another Native web host (the other app, the bare domain) stays out of the browser`() {
        assertTrue(prod.blocks("https://pos.native-app.my.id/"))
        assertTrue(prod.blocks("https://native-app.my.id/"))
        assertTrue(prod.blocks("https://POS.Native-App.my.id/"))
        assertTrue(uat.blocks("https://emp.native-app.my.id/me"))
        assertFalse(prod.blocks("https://emp.native-app.my.id/me"))
    }

    @Test
    fun `the app's own and allowed hosts are left to Capacitor (it loads them in the WebView)`() {
        assertFalse(prod.blocks("https://emp.native-app.my.id/me"))
        // The shared token issuer lives on the Business host (ADR 0049 P5) — login stays in-app.
        assertFalse(prod.blocks("https://app.native-app.my.id/auth/realms/native/protocol/openid-connect/auth"))
        assertFalse(uat.blocks("https://a8.tailbf9662.ts.net:8443/auth/realms/native/protocol/openid-connect/auth"))
        assertFalse(uat.blocks("https://a8.tailbf9662.ts.net:10000/me"))
    }

    @Test
    fun `the legal pages (LEGAL_BASE_URL is always the prod business host) open in-app on every build`() {
        for (shell in listOf(prod, uat)) {
            assertFalse(shell.blocks("https://app.native-app.my.id/privacy.html"))
            assertFalse(shell.blocks("https://app.native-app.my.id/delete-account.html"))
        }
    }

    @Test
    fun `the app host on a foreign scheme is blocked, since Capacitor's own check would send it to Chrome`() {
        val shell = Shell("https", "emp.native-app.my.id", emptySet())
        assertTrue(shell.blocks("http://emp.native-app.my.id/me"))
        assertFalse(shell.blocks("https://emp.native-app.my.id/me"))
    }

    @Test
    fun `genuinely external links and app intents keep opening outside`() {
        assertFalse(prod.blocks("https://wa.me/6281234567890"))
        assertFalse(prod.blocks("https://play.google.com/store/apps/details?id=ru.a402d.rawbtprinter"))
        assertFalse(prod.blocks("intent:base64,AAAA#Intent;scheme=rawbt;package=ru.a402d.rawbtprinter;end;"))
        assertFalse(prod.blocks("tel:+6281234567890"))
        assertFalse(prod.blocks("mailto:support@native-app.my.id"))
    }

    @Test
    fun `a lookalike domain is not mistaken for a Native host`() {
        assertFalse(prod.blocks("https://evilnative-app.my.id/"))
        assertFalse(prod.blocks("https://native-app.my.id.example.com/"))
    }
}
