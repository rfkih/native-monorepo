package id.co.nativeapp.till

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live link to one printer (ADR 0043): a dumb byte pipe the plugin holds between connect() and
 * disconnect(). ESC/POS encoding stays 100% in the web layer — these classes never interpret the
 * bytes. All methods are called on the plugin's single-thread executor (serialized by design).
 */
interface PrinterConnection {
    @Throws(Exception::class)
    fun write(bytes: ByteArray)

    fun close()
}

/** Carries the D4 reject code (`ConnectFailureReason`) across the bridge verbatim. */
class BridgeException(val code: String, message: String) : Exception(message)

/** Bluetooth Classic — an RFCOMM/SPP socket to a bonded printer. The ADR 0041 gap closer. */
class SppPrinterConnection private constructor(
    private val socket: BluetoothSocket,
) : PrinterConnection {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CONNECT_TIMEOUT_MS = 10_000L

        @SuppressLint("MissingPermission") // the plugin gates on BLUETOOTH_CONNECT first
        fun open(device: BluetoothDevice): SppPrinterConnection {
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            // BluetoothSocket.connect() has no timeout parameter — bound it by closing the socket
            // from a timer (close() makes the blocking connect throw promptly).
            val timer = Timer(true)
            val timedOut = AtomicBoolean(false)
            timer.schedule(object : TimerTask() {
                override fun run() {
                    timedOut.set(true)
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                }
            }, CONNECT_TIMEOUT_MS)
            try {
                socket.connect()
            } catch (e: IOException) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                val detail = if (timedOut.get()) "timed out" else (e.message ?: "I/O error")
                throw BridgeException("unknown", "SPP connect failed: $detail")
            } finally {
                timer.cancel()
            }
            // The watchdog may have fired between connect() returning and cancel() — the socket
            // is then closed under us; fail now rather than on the first write.
            if (timedOut.get()) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                throw BridgeException("unknown", "SPP connect timed out")
            }
            return SppPrinterConnection(socket)
        }
    }

    override fun write(bytes: ByteArray) {
        try {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
            // Cheap SPP clones drop tail bytes when the link goes quiet immediately after flush.
            Thread.sleep(150)
        } catch (e: IOException) {
            throw BridgeException("unknown", "SPP write failed: ${e.message}")
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

/** USB host — bulk-OUT transfer to an attached printer (the WebView has no WebUSB; this is it). */
class UsbPrinterConnection private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val endpoint: UsbEndpoint,
) : PrinterConnection {

    companion object {
        private const val CHUNK = 4096
        private const val TRANSFER_TIMEOUT_MS = 5_000

        /** Prefer the printer-class (0x07) interface; clones often declare class only there. */
        fun findBulkOut(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
            var fallback: Pair<UsbInterface, UsbEndpoint>? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val isBulkOut = ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    if (!isBulkOut) continue
                    if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return iface to ep
                    if (fallback == null) fallback = iface to ep
                }
            }
            return fallback
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbPrinterConnection {
            val (iface, endpoint) = findBulkOut(device)
                ?: throw BridgeException("noEndpoint", "no bulk-OUT endpoint on ${device.deviceName}")
            val connection = manager.openDevice(device)
                ?: throw BridgeException("blocked", "could not open ${device.deviceName}")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw BridgeException("inUse", "could not claim the printer interface")
            }
            return UsbPrinterConnection(connection, iface, endpoint)
        }
    }

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(CHUNK, bytes.size - offset)
            val sent = connection.bulkTransfer(endpoint, bytes, offset, length, TRANSFER_TIMEOUT_MS)
            if (sent < 0) throw BridgeException("unknown", "USB bulk transfer failed at offset $offset")
            offset += sent
        }
    }

    override fun close() {
        try {
            connection.releaseInterface(iface)
        } catch (_: Exception) {
        }
        connection.close()
    }
}
