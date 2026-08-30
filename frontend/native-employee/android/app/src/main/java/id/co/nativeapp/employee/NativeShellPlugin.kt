package id.co.nativeapp.employee

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * The window.Capacitor.Plugins.NativeShell bridge — app-level actions the WebView cannot perform
 * itself. Currently one: `minimize()`, the "Exit" of the web app's back-guard confirm dialog
 * (the web layer intercepts hardware Back via the History API — see the console's useBackGuard —
 * and needs a way to actually background the app when the employee confirms at the home screen).
 * Mirrors the MainActivity back handler's choice: background via moveTaskToBack, never exit.
 * Identical to the Till app's NativeShellPlugin (each shell keeps its own copy, per package).
 */
@CapacitorPlugin(name = "NativeShell")
class NativeShellPlugin : Plugin() {

    companion object {
        private const val API_VERSION = 1
    }

    /** Feature probe for the web layer (mirrors the Till app's API_VERSION convention). */
    @PluginMethod
    fun getInfo(call: PluginCall) {
        call.resolve(JSObject().put("apiVersion", API_VERSION))
    }

    @PluginMethod
    fun minimize(call: PluginCall) {
        val act = activity
        if (act == null) {
            call.reject("no activity")
            return
        }
        act.runOnUiThread {
            act.moveTaskToBack(true)
            call.resolve()
        }
    }
}
