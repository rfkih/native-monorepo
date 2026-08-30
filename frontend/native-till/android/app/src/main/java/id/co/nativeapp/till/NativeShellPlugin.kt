package id.co.nativeapp.till

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * The window.Capacitor.Plugins.NativeShell bridge — app-level actions the WebView cannot perform
 * itself. Web access goes through the console's lib/nativeShell.ts, which treats every method as
 * optional (old APKs simply lack it).
 *
 *  • minimize() — background the app (moveTaskToBack), the "Exit" of the back-guard confirm
 *    dialog. Mirrors the MainActivity back handler's choice: background, never exit.
 *  • saveFile() — write a base64 payload into the device's Downloads via MediaStore. The WebView
 *    does NOTHING for <a download>/blob clicks (no DownloadListener can help — blob: URLs are
 *    in-page only), so every CSV/bank-file export was silently dead in the shell; the web download
 *    helpers call this instead when the bridge is present. MediaStore.Downloads needs API 29+;
 *    older devices get a typed rejection the web layer explains.
 *  • printPage() — hand the CURRENT page to the Android print framework. window.print() is a
 *    no-op in a WebView; createPrintDocumentAdapter honours the same @media print CSS, so the
 *    printed output matches the browser path.
 */
@CapacitorPlugin(name = "NativeShell")
class NativeShellPlugin : Plugin() {

    companion object {
        private const val API_VERSION = 2
        const val CODE_UNSUPPORTED = "UNSUPPORTED"
    }

    /** Feature probe for the web layer (mirrors NativePrint's API_VERSION convention). */
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

    @PluginMethod
    fun saveFile(call: PluginCall) {
        val base64 = call.getString("base64")
        val filename = call.getString("filename")
        if (base64 == null || filename == null) {
            call.reject("base64 and filename are required")
            return
        }
        val mimeType = call.getString("mimeType") ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-Q needs WRITE_EXTERNAL_STORAGE + legacy paths — not worth the permission dance
            // for the fleet's Android 10+ devices. Typed code so the web layer can explain.
            call.reject("Downloads require Android 10+", CODE_UNSUPPORTED)
            return
        }
        Thread {
            val resolver = context.contentResolver
            var uri: android.net.Uri? = null
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    call.reject("could not create the download entry")
                    return@Thread
                }
                resolver.openOutputStream(uri).use { stream ->
                    if (stream == null) {
                        throw IllegalStateException("could not open the download for writing")
                    }
                    stream.write(bytes)
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                call.resolve(JSObject().put("uri", uri.toString()))
            } catch (e: Exception) {
                // Remove the half-written IS_PENDING row — otherwise it lingers invisible for days
                // (this is exactly the storage-full path the web toast explains).
                uri?.let { runCatching { resolver.delete(it, null, null) } }
                call.reject("save failed: ${e.message}")
            }
        }.start()
    }

    @PluginMethod
    fun printPage(call: PluginCall) {
        val act = activity
        if (act == null) {
            call.reject("no activity")
            return
        }
        act.runOnUiThread {
            try {
                val jobName = call.getString("jobName") ?: "Native"
                val printManager = act.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = bridge.webView.createPrintDocumentAdapter(jobName)
                printManager.print(jobName, adapter, PrintAttributes.Builder().build())
                call.resolve()
            } catch (e: Exception) {
                call.reject("print failed: ${e.message}")
            }
        }
    }
}
