package id.co.nativeapp.employee

import android.net.Uri
import com.getcapacitor.Logger
import com.getcapacitor.Plugin
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.util.HostMask

/**
 * Enforces [NavigationPolicy] at Capacitor's one breakout point: Bridge.launchIntent asks every
 * plugin's shouldOverrideLoad FIRST, before it would ACTION_VIEW a URL into the system browser.
 * No JS surface. The Native-owned domains come from capacitor.config.ts
 * (plugins.AppOnlyNavigation.ownDomains); "allowed" mirrors Capacitor's own check exactly
 * (server.url's host + the allowNavigation HostMask), so anything Capacitor would load in the
 * WebView is untouched.
 */
@CapacitorPlugin(name = "AppOnlyNavigation")
class AppOnlyNavigationPlugin : Plugin() {

    private var appScheme: String? = null
    private var appHost: String? = null
    private var allowNavigation: HostMask = HostMask.Parser.parse(null as Array<String>?)
    private var ownDomains: List<String> = emptyList()

    override fun load() {
        val appUri = Uri.parse(bridge.appUrl)
        appScheme = appUri.scheme
        appHost = appUri.host
        allowNavigation = HostMask.Parser.parse(bridge.config.allowNavigation)
        ownDomains = config.getArray("ownDomains", emptyArray()).toList()
    }

    override fun shouldOverrideLoad(url: Uri): Boolean? {
        val blocked = NavigationPolicy.blocks(
            url.scheme,
            url.host,
            appScheme,
            appHost,
            { allowNavigation.matches(it) },
            ownDomains,
        )
        if (!blocked) return null
        // Host only — the URL itself can carry auth codes/state.
        Logger.warn("AppOnlyNavigation", "blocked a navigation out of the app to ${url.host}")
        return true
    }
}
