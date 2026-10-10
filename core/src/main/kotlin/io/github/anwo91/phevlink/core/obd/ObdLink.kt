package io.github.anwo91.phevlink.core.obd

import java.io.IOException

/**
 * A byte pipe to an ELM327-compatible OBD adapter. Implemented by the app for
 * Bluetooth Classic (SPP), Bluetooth LE (UART-style GATT) and TCP (Wi-Fi
 * dongles), and by [io.github.anwo91.phevlink.core.obd.emulator.FakeElm] for tests.
 *
 * Implementations must be safe to call from one coroutine at a time; the ELM
 * driver never calls [send] and [receive] concurrently.
 */
interface ObdLink {
    /** Human-readable description for logs, e.g. "Bluetooth OBDII (00:1D:A5:...)". */
    val description: String

    /** Send raw bytes to the adapter. Throws [IOException] if the link is down. */
    suspend fun send(bytes: ByteArray)

    /**
     * Wait up to [timeoutMs] for the next chunk of bytes from the adapter.
     * Returns null on timeout. Throws [IOException] once the link is closed or lost.
     * Chunks can be any size (BLE adapters deliver ~20 bytes at a time).
     */
    suspend fun receive(timeoutMs: Long): ByteArray?

    /** Close the link. Idempotent. */
    fun close()
}
