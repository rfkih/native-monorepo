package id.co.nativeapp.till

/**
 * "The app stays the app" (field bug 2026-10-19): a navigation to a Native web host the shell does
 * not serve must never be handed to the system browser.
 *
 * Capacitor's Bridge.launchIntent opens ANY top-frame (and sub-frame) navigation whose host is
 * neither server.url's nor in allowNavigation via ACTION_VIEW — i.e. Chrome. For a Native host that
 * means the user silently lands on the WEB console (twice in the field: a cross-host Keycloak login,
 * then the offline page retrying to the UAT funnel), confused about which "app" they are in. Such a
 * navigation is always a shell bug, so it is cancelled instead (the page stays put).
 *
 * Pure (no Android types) so the JVM unit test pins it. Deliberately narrow: only http(s) to a
 * Native-owned domain is blocked. Genuinely external links (wa.me, Play) and app intents
 * (intent:/RawBT printing, tel:, mailto:) keep Capacitor's default and open outside.
 */
object NavigationPolicy {

    /**
     * true = cancel the navigation; false = defer to Capacitor's default policy. "Deferred" mirrors
     * what Capacitor itself keeps in the WebView: the app host on the app's OWN scheme (its check is
     * scheme-strict — http://<app host> would go to Chrome), or an allowNavigation host (host-only).
     */
    fun blocks(
        scheme: String?,
        host: String?,
        appScheme: String?,
        appHost: String?,
        isAllowedHost: (String) -> Boolean,
        ownDomains: List<String>,
    ): Boolean {
        if (scheme == null || host == null) return false
        if (!scheme.equals("https", ignoreCase = true) && !scheme.equals("http", ignoreCase = true)) return false
        val h = host.lowercase()
        if (h == appHost?.lowercase() && scheme.equals(appScheme, ignoreCase = true)) return false
        if (isAllowedHost(h)) return false
        return ownDomains.any { domain ->
            val d = domain.lowercase()
            h == d || h.endsWith(".$d")
        }
    }
}
