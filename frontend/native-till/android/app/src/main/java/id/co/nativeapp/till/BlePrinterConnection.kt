package id.co.nativeapp.till

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BLE GATT byte pipe (ADR 0043). Mirrors the web BLE transport's behavior — hunt for a writable
 * characteristic across the print-service UUIDs the clone boards actually ship, write in small
 * chunks, wait for each write callback so the board's buffer never overruns.
 *
 * Android's GATT API is callback-based; the plugin executor is a plain worker thread, so the
 * open/write paths block on latches armed by [LatchingGattCallback]. All calls are serialized by
 * the plugin's single-thread executor.
 */
@SuppressLint("MissingPermission") // the plugin gates on BLUETOOTH_CONNECT before any BLE call
class BlePrinterConnection private constructor(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
    private val callback: LatchingGattCallback,
) : PrinterConnection {

    companion object {
        /** Same preference order as the web transport's BLE_PRINT_SERVICES. */
        private val PRINT_SERVICE_UUIDS = listOf(
            UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455"),
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        )
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 5_000L

        fun open(context: Context, device: BluetoothDevice): BlePrinterConnection {
            val callback = LatchingGattCallback()
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (!callback.awaitConnected(CONNECT_TIMEOUT_MS)) {
                gatt.close()
                throw BridgeException("unknown", "BLE connect failed or timed out")
            }
            if (!gatt.discoverServices() || !callback.awaitServices(CONNECT_TIMEOUT_MS)) {
                gatt.close()
                throw BridgeException("unknown", "BLE service discovery failed")
            }
            val characteristic = pickWritable(gatt)
            if (characteristic == null) {
                gatt.close()
                throw BridgeException("noEndpoint", "no writable characteristic")
            }
            // Bigger MTU = fewer chunks. Best effort — chunk size falls back to 20 (MTU 23).
            if (gatt.requestMtu(185)) callback.awaitMtu(2_000L)
            return BlePrinterConnection(gatt, characteristic, callback)
        }

        private fun pickWritable(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
            val services = gatt.services ?: return null
            val ordered = services.sortedBy { s ->
                val i = PRINT_SERVICE_UUIDS.indexOf(s.uuid)
                if (i == -1) PRINT_SERVICE_UUIDS.size else i
            }
            for (service in ordered) {
                for (ch in service.characteristics) {
                    val props = ch.properties
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ||
                        props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                    ) {
                        return ch
                    }
                }
            }
            return null
        }
    }

    override fun write(bytes: ByteArray) {
        val useNoResponse =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + callback.chunkSize, bytes.size)
            // The value+writeCharacteristic pair is deprecated on API 33+ in favor of the
            // (char, value, type) overload; the legacy form still works on every level we
            // support, so one code path keeps this simple until minSdk reaches 33.
            @Suppress("DEPRECATION")
            characteristic.value = bytes.copyOfRange(offset, end)
            characteristic.writeType = if (useNoResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            callback.armWrite()
            @Suppress("DEPRECATION")
            if (!gatt.writeCharacteristic(characteristic)) {
                throw BridgeException("unknown", "BLE write rejected at offset $offset")
            }
            if (!callback.awaitWrite(WRITE_TIMEOUT_MS)) {
                throw BridgeException("unknown", "BLE write timed out at offset $offset")
            }
            offset = end
        }
    }

    override fun close() {
        try {
            gatt.disconnect()
        } catch (_: Exception) {
        }
        gatt.close()
    }
}

/** Bridges the GATT callbacks to the plugin's blocking worker thread. */
internal class LatchingGattCallback : BluetoothGattCallback() {
    private val connected = CountDownLatch(1)
    private val services = CountDownLatch(1)
    private val mtu = CountDownLatch(1)

    @Volatile private var connectOk = false

    @Volatile private var servicesOk = false

    @Volatile private var writeLatch: CountDownLatch? = null

    @Volatile private var writeOk = false

    /** Payload per write: ATT MTU minus the 3-byte write header (20 until onMtuChanged). */
    @Volatile var chunkSize = 20
        private set

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connectOk = true
        }
        // Success or failure, release the waiter (a later drop just fails the pending write).
        connected.countDown()
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            services.countDown()
            writeLatch?.countDown()
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        servicesOk = status == BluetoothGatt.GATT_SUCCESS
        services.countDown()
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtuSize: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) chunkSize = (mtuSize - 3).coerceIn(20, 509)
        mtu.countDown()
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        writeOk = status == BluetoothGatt.GATT_SUCCESS
        writeLatch?.countDown()
    }

    fun awaitConnected(timeoutMs: Long): Boolean =
        connected.await(timeoutMs, TimeUnit.MILLISECONDS) && connectOk

    fun awaitServices(timeoutMs: Long): Boolean =
        services.await(timeoutMs, TimeUnit.MILLISECONDS) && servicesOk

    fun awaitMtu(timeoutMs: Long) {
        mtu.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun armWrite() {
        writeOk = false
        writeLatch = CountDownLatch(1)
    }

    fun awaitWrite(timeoutMs: Long): Boolean =
        (writeLatch?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: false) && writeOk
}
