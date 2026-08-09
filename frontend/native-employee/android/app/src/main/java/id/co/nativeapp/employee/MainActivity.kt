package id.co.nativeapp.employee

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.getcapacitor.BridgeActivity

/**
 * Native Karyawan — a thin Capacitor shell (ADR 0049 P5) that renders the live console origin's
 * role-gated `/me` employee self-service surface (payslips, time-off, claims, PII profile, own
 * sales/commission). Pure self-service: unlike the Business/Till app (ADR 0043), there is nothing
 * to print here, so this shell registers no native plugins.
 */
class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The console layout is designed at CSS-pixel scale; Android's accessibility font size
        // otherwise multiplies into WebView text zoom and shatters the grid layouts.
        bridge.webView.settings.textZoom = 100
        // Back = previous in-app page, via the MODERN dispatcher. targetSdk 36 enables
        // predictive back by default, and Android 16 stops calling the deprecated
        // onBackPressed() entirely (a field report on the Till app, ADR 0043) — the androidx
        // callback rides both the legacy dispatch (old Androids) and OnBackInvoked (13+).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = bridge?.webView
                // Never step back INTO an IdP page (same-origin /auth/* = Keycloak): an
                // authenticated session immediately re-redirects forward, so back would
                // only bounce — from the post-login screen back means "leave the app".
                val list = webView?.copyBackForwardList()
                val prevUrl = list
                    ?.takeIf { it.currentIndex > 0 }
                    ?.getItemAtIndex(list.currentIndex - 1)
                    ?.url
                val backIntoAuth = prevUrl != null && prevUrl.contains("/auth/")
                if (webView != null && webView.canGoBack() && !backIntoAuth) {
                    webView.goBack()
                } else {
                    // At history root a stray back gesture backgrounds the app rather than
                    // exiting it (mirrors the Till app's navigation model) — an accidental exit
                    // loses the employee's place mid-task (e.g. a half-filled claim form).
                    moveTaskToBack(true)
                }
            }
        })
    }
}
