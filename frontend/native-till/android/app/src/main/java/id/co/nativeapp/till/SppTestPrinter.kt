package id.co.nativeapp.till

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.util.UUID

/**
 * P0 spike: dumb byte pipe to a bonded Bluetooth-Classic (SPP) printer.
 * ESC/POS encoding stays in the web layer (ADR 0043) — this class never
 * interprets the bytes it writes.
 */
object SppTestPrinter {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Fixed ASCII-only ESC/POS sequence: init, centered title, body, feed, partial cut. */
    val TEST_BYTES: ByteArray = buildTestBytes()

    private fun buildTestBytes(): ByteArray {
        val out = ArrayList<Byte>()
        fun cmd(vararg b: Int) = b.forEach { out.add(it.toByte()) }
        fun line(s: String) {
            s.forEach { out.add(it.code.toByte()) }
            out.add(0x0A)
        }
        cmd(0x1B, 0x40)             // ESC @   initialize
        cmd(0x1B, 0x61, 0x01)       // ESC a 1 center
        cmd(0x1B, 0x45, 0x01)       // ESC E 1 bold on
        line("NATIVE TILL")
        cmd(0x1B, 0x45, 0x00)       // bold off
        line("P0 NATIVE BRIDGE TEST")
        cmd(0x1B, 0x61, 0x00)       // left
        line("--------------------------------")
        line("Bluetooth Classic (SPP): OK")
        line("If you can read this, the")
        line("ADR 0041 gap is closed.")
        line("--------------------------------")
        cmd(0x1B, 0x64, 0x03)       // ESC d 3 feed 3
        cmd(0x1D, 0x56, 0x42, 0x00) // GS V B 0 partial cut
        return out.toByteArray()
    }

    /**
     * Opens an RFCOMM/SPP socket to the bonded [device], writes [bytes] verbatim,
     * drains, closes. Blocking — call off the main thread. Throws on I/O failure.
     */
    @SuppressLint("MissingPermission") // callers gate on BLUETOOTH_CONNECT first
    fun write(device: BluetoothDevice, bytes: ByteArray) {
        device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
            socket.connect()
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
            // Cheap SPP printers drop tail bytes when the socket closes right after flush.
            Thread.sleep(400)
        }
    }
}
