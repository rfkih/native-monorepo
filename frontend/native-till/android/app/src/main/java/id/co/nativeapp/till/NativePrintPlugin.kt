package id.co.nativeapp.till

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The window.NativePrint bridge (ADR 0043, D4) — the app's one native capability.
 *
 * A dumb byte pipe to thermal printers over the three hardware links the WebView cannot reach
 * itself: Bluetooth Classic (SPP), BLE (GATT) and USB host. The web layer picks the device
 * (listDevices → connect(deviceId)), produces the ESC/POS bytes, and base64s them across the
 * bridge; nothing here interprets them. Holds at most ONE live connection — mirroring the web
 * usePrinter model. Reject codes are drawn from the console's ConnectFailureReason set
 * (cancelled / blocked / inUse / noEndpoint / unknown) so classifyConnectError passes them through.
 */
@CapacitorPlugin(
    name = "NativePrint",
    permissions = [
        Permission(alias = NativePrintPlugin.BT_ALIAS, strings = [Manifest.permission.BLUETOOTH_CONNECT])
    ]
)
class NativePrintPlugin : Plugin() {

    companion object {
        const val BT_ALIAS = "bluetooth"
        private const val API_VERSION = 1
        private const val USB_ID_PREFIX = "usb:"
        private const val USB_PERMISSION_ACTION = "id.co.nativeapp.till.USB_PERMISSION"
        private const val USB_CONSENT_TIMEOUT_MS = 90_000L
    }

    /** Serializes every device operation — connect/write/disconnect never interleave. */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var connection: PrinterConnection? = null

    override fun handleOnDestroy() {
        executor.execute {
            connection?.close()
            connection = null
        }
        executor.shutdown()
    }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun usbManager(): UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private fun needsRuntimePermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            getPermissionState(BT_ALIAS) != PermissionState.GRANTED

    private fun usbDeviceId(device: UsbDevice): String =
        USB_ID_PREFIX + String.format("%04x:%04x", device.vendorId, device.productId)

    @PluginMethod
    fun getInfo(call: PluginCall) {
        call.resolve(JSObject().put("apiVersion", API_VERSION))
    }

    // ------------------------------------------------------------------ listDevices

    @PluginMethod
    fun listDevices(call: PluginCall) {
        if (needsRuntimePermission()) {
            requestPermissionForAlias(BT_ALIAS, call, "listDevicesPermissionCallback")
            return
        }
        resolveDeviceList(call)
    }

    @PermissionCallback
    private fun listDevicesPermissionCallback(call: PluginCall) {
        if (needsRuntimePermission()) {
            call.reject("Bluetooth permission denied", "blocked")
            return
        }
        resolveDeviceList(call)
    }

    @SuppressLint("MissingPermission") // gated via needsRuntimePermission()
    private fun resolveDeviceList(call: PluginCall) {
        val devices = JSArray()

        // Bonded Bluetooth — pairing stays in Android Settings, so no SCAN permission is needed.
        val adapter = adapter()
        if (adapter != null && adapter.isEnabled) {
            adapter.bondedDevices.sortedBy { it.name ?: it.address }.forEach { device ->
                val kind =
                    if (device.type == BluetoothDevice.DEVICE_TYPE_LE) "ble" else "classic"
                devices.put(
                    JSObject()
                        .put("id", device.address)
                        .put("name", device.name ?: device.address)
                        .put("kind", kind)
                        .put("bonded", true)
                )
            }
        }

        // Attached USB devices that expose a bulk-OUT endpoint (i.e. look like printers).
        // vid:pid is the stable key (survives replug/reboot for re-attach); two IDENTICAL
        // printers collide on it, so only duplicates get the port path appended.
        val usbDevices = usbManager()?.deviceList?.values.orEmpty()
            .filter { UsbPrinterConnection.findBulkOut(it) != null }
        val idCounts = usbDevices.groupingBy { usbDeviceId(it) }.eachCount()
        usbDevices.forEach { device ->
            val base = usbDeviceId(device)
            val id = if ((idCounts[base] ?: 0) > 1) "$base#${device.deviceName}" else base
            devices.put(
                JSObject()
                    .put("id", id)
                    .put("name", device.productName ?: device.deviceName)
                    .put("kind", "usb")
                    .put("bonded", true)
            )
        }

        call.resolve(JSObject().put("devices", devices))
    }

    // ------------------------------------------------------------------ connect

    @PluginMethod
    fun connect(call: PluginCall) {
        val deviceId = call.getString("deviceId")
        if (deviceId.isNullOrBlank()) {
            call.reject("deviceId is required", "cancelled")
            return
        }
        if (deviceId.startsWith(USB_ID_PREFIX)) {
            connectUsb(call, deviceId)
            return
        }
        if (needsRuntimePermission()) {
            requestPermissionForAlias(BT_ALIAS, call, "connectPermissionCallback")
            return
        }
        connectBluetooth(call, deviceId)
    }

    @PermissionCallback
    private fun connectPermissionCallback(call: PluginCall) {
        if (needsRuntimePermission()) {
            call.reject("Bluetooth permission denied", "blocked")
            return
        }
        val deviceId = call.getString("deviceId") ?: return call.reject("deviceId is required", "cancelled")
        connectBluetooth(call, deviceId)
    }

    @SuppressLint("MissingPermission") // gated via needsRuntimePermission()
    private fun connectBluetooth(call: PluginCall, deviceId: String) {
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            call.reject("Bluetooth unavailable or off", "unknown")
            return
        }
        val device = adapter.bondedDevices.firstOrNull { it.address.equals(deviceId, ignoreCase = true) }
        if (device == null) {
            call.reject("No bonded device with id $deviceId", "unknown")
            return
        }
        openOnExecutor(call) {
            if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
                BlePrinterConnection.open(context, device)
            } else {
                SppPrinterConnection.open(device)
            }
        }
    }

    private fun connectUsb(call: PluginCall, deviceId: String) {
        val manager = usbManager()
        val device = manager?.deviceList?.values?.firstOrNull {
            deviceId == usbDeviceId(it) || deviceId == "${usbDeviceId(it)}#${it.deviceName}"
        }
        if (manager == null || device == null) {
            call.reject("No attached USB device with id $deviceId", "unknown")
            return
        }
        if (manager.hasPermission(device)) {
            openOnExecutor(call) { UsbPrinterConnection.open(manager, device) }
            return
        }
        // Android's per-device USB consent dialog — resolved via a one-shot broadcast. The dialog
        // can die without ever broadcasting (activity teardown, OEM quirks), so a watchdog
        // unregisters + rejects rather than leaving the JS promise — and with it the settings
        // "Connecting…" state — hung forever. This is the bridge's only unbounded wait otherwise.
        val settled = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!settled.compareAndSet(false, true)) return
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: IllegalArgumentException) {
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (!granted) {
                    // The operator said no in the system dialog — the settings UI treats
                    // `cancelled` silently, exactly like dismissing a browser chooser.
                    call.reject("USB permission declined", "cancelled")
                    return
                }
                openOnExecutor(call) { UsbPrinterConnection.open(manager, device) }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(USB_PERMISSION_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Handler(Looper.getMainLooper()).postDelayed({
            if (settled.compareAndSet(false, true)) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                }
                call.reject("USB permission request timed out", "cancelled")
            }
        }, USB_CONSENT_TIMEOUT_MS)
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(USB_PERMISSION_ACTION).setPackage(context.packageName),
            flags
        )
        manager.requestPermission(device, pending)
    }

    /** Close whatever was held, open the new link, resolve/reject with the D4 code. */
    private fun openOnExecutor(call: PluginCall, open: () -> PrinterConnection) {
        executor.execute {
            try {
                connection?.close()
                connection = null
                connection = open()
                call.resolve()
            } catch (e: BridgeException) {
                call.reject(e.message, e.code)
            } catch (e: Exception) {
                call.reject("Connect failed: ${e.message}", "unknown")
            }
        }
    }

    // ------------------------------------------------------------------ write / disconnect

    @PluginMethod
    fun write(call: PluginCall) {
        val base64 = call.getString("base64")
        if (base64.isNullOrEmpty()) {
            call.reject("base64 is required", "unknown")
            return
        }
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            call.reject("invalid base64 payload", "unknown")
            return
        }
        executor.execute {
            val live = connection
            if (live == null) {
                call.reject("no printer connected", "unknown")
                return@execute
            }
            try {
                live.write(bytes)
                call.resolve()
            } catch (e: Exception) {
                // A failed write means the link is gone (printer off / out of range / unplugged).
                // Drop it so the web layer's printReceipt fallback + re-attach semantics hold.
                try {
                    live.close()
                } catch (_: Exception) {
                }
                connection = null
                val code = (e as? BridgeException)?.code ?: "unknown"
                call.reject("Print failed: ${e.message}", code)
            }
        }
    }

    @PluginMethod
    fun disconnect(call: PluginCall) {
        executor.execute {
            connection?.close()
            connection = null
            call.resolve()
        }
    }
}
