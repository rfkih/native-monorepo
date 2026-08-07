package id.co.nativeapp.till

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {

    companion object {
        // The debug button's permission flow is deliberately independent of the plugin's
        // requestPermissionForAlias flow (which rides the AndroidX Activity Result API) —
        // this legacy request code cannot collide with Capacitor's plugin permissions.
        private const val DEBUG_PRINT_PERMISSION_REQUEST = 4127
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(NativePrintPlugin::class.java)
        super.onCreate(savedInstanceState)
        // A till must never dim mid-service (ADR 0043 D6).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The POS layout is designed at CSS-pixel scale; Android's accessibility font size
        // otherwise multiplies into WebView text zoom and shatters the grid/receipt layouts.
        bridge.webView.settings.textZoom = 100
        // Hardware sanity check independent of the deployed console build: prints fixed
        // test bytes over SPP. Debug builds only; the real path is the console's
        // printer settings (the 'native' transport tile).
        if (BuildConfig.DEBUG) {
            addDebugPrintButton()
        }
        // Back = previous in-app page, via the MODERN dispatcher. targetSdk 36 enables
        // predictive back by default, and Android 16 stops calling the deprecated
        // onBackPressed() entirely — the shell-v3 override silently became dead code and
        // every back press closed the app (field report). The androidx callback rides both
        // the legacy dispatch (old Androids) and OnBackInvoked (13+), one code path.
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
                    // At history root a stray back gesture must background the till, never
                    // exit it — an accidental exit mid-shift loses the operator's place.
                    moveTaskToBack(true)
                }
            }
        })
    }

    private fun addDebugPrintButton() {
        val button = Button(this).apply {
            text = "🖨 TEST"
            alpha = 0.55f
            setOnClickListener { onDebugPrintTapped() }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            bottomMargin = dp(28)
            marginEnd = dp(12)
        }
        addContentView(button, params)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun onDebugPrintTapped() {
        if (!hasBluetoothPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                DEBUG_PRINT_PERMISSION_REQUEST
            )
            return
        }
        showPrinterChooser()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DEBUG_PRINT_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showPrinterChooser()
            } else {
                toast("Bluetooth permission denied - cannot reach the printer")
            }
        }
    }

    @SuppressLint("MissingPermission") // gated by hasBluetoothPermission()
    private fun showPrinterChooser() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Bluetooth is off - enable it and try again")
            return
        }
        val devices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        if (devices.isEmpty()) {
            toast("No bonded devices - pair the printer in Android Settings first")
            return
        }
        val labels = devices.map { "${it.name ?: "(unnamed)"}  ${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Print test to bonded device")
            .setItems(labels) { _, index -> printTestTo(devices[index]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun printTestTo(device: BluetoothDevice) {
        toast("Printing test receipt...")
        Thread {
            try {
                SppTestPrinter.write(device, SppTestPrinter.TEST_BYTES)
                runOnUiThread { toast("Test receipt sent - check the printer") }
            } catch (e: Exception) {
                runOnUiThread { toast("Print failed: ${e.message}") }
            }
        }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
