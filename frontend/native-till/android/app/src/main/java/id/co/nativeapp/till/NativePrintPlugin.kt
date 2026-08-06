package id.co.nativeapp.till

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
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

/**
 * P0 spike of the window.NativePrint bridge (ADR 0043, D4).
 *
 * Scope: list bonded Bluetooth-Classic devices + one-shot test print of fixed
 * ESC/POS bytes over SPP. The full contract (apiVersion / connect / write /
 * disconnect, BLE + USB kinds) lands in P1 — reject codes already follow the
 * D4 mapping onto the web layer's ConnectFailureReason.
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
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun needsRuntimePermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            getPermissionState(BT_ALIAS) != PermissionState.GRANTED

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
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            call.reject("Bluetooth unavailable or off", "unknown")
            return
        }
        val devices = JSArray()
        adapter.bondedDevices.sortedBy { it.name ?: it.address }.forEach { device ->
            devices.put(
                JSObject()
                    .put("id", device.address)
                    .put("name", device.name ?: device.address)
                    .put("kind", "classic")
                    .put("bonded", true)
            )
        }
        call.resolve(JSObject().put("devices", devices))
    }

    /** One-shot: write the fixed P0 test bytes to the bonded device with MAC `deviceId`. */
    @PluginMethod
    fun printTest(call: PluginCall) {
        if (needsRuntimePermission()) {
            requestPermissionForAlias(BT_ALIAS, call, "printTestPermissionCallback")
            return
        }
        doPrintTest(call)
    }

    @PermissionCallback
    private fun printTestPermissionCallback(call: PluginCall) {
        if (needsRuntimePermission()) {
            call.reject("Bluetooth permission denied", "blocked")
            return
        }
        doPrintTest(call)
    }

    @SuppressLint("MissingPermission") // gated via needsRuntimePermission()
    private fun doPrintTest(call: PluginCall) {
        val deviceId = call.getString("deviceId")
        if (deviceId.isNullOrBlank()) {
            call.reject("deviceId is required", "cancelled")
            return
        }
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
        executor.execute {
            try {
                SppTestPrinter.write(device, SppTestPrinter.TEST_BYTES)
                call.resolve()
            } catch (e: Exception) {
                call.reject("Print failed: ${e.message}", "unknown")
            }
        }
    }
}
