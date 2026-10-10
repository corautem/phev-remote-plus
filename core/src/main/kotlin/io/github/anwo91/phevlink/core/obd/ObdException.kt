package io.github.anwo91.phevlink.core.obd

import java.io.IOException

/**
 * Everything that can go wrong talking to the car through an ELM327 adapter.
 * [Elm327] throws only these (plus [kotlinx.coroutines.CancellationException]).
 * [ObdClient] catches them and turns them into [ObdState.message] texts.
 */
sealed class ObdException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The adapter printed `NO DATA`: nobody answered the request (ECU absent, request unsupported or car asleep). */
    class NoData(val command: String) : ObdException("NO DATA for $command")

    /** `UNABLE TO CONNECT` / silent bus: the adapter could not talk to the car at all (ignition off, wrong protocol). */
    class UnableToConnect(val command: String, val detail: String) : ObdException("Car not answering $command ($detail)")

    /** The adapter answered with an error or something unusable (`?`, `CAN ERROR`, `BUFFER FULL`, `STOPPED` ...). */
    class AdapterError(val text: String, val command: String) : ObdException("Adapter error for $command: $text")

    /** No `>` prompt within [waitedMs]. [partial] is whatever arrived before giving up. */
    class Timeout(val command: String, val waitedMs: Long, val partial: String) :
        ObdException("No answer from the adapter to $command within $waitedMs ms" + if (partial.isBlank()) "" else " (got '${partial.trim().take(80)}')")

    /** The byte link (Bluetooth/TCP) failed. The [Elm327] cannot be used any more. */
    class LinkLost(val ioException: IOException) : ObdException("Link to the adapter lost: ${ioException.message}", ioException)

    /**
     * A multi-frame (ISO-TP) reply stopped after [received] of [expected] bytes. Typical cause: the adapter
     * did not send the CAN flow-control frame to the ECU (cheap clones that reject `ATFCSH`).
     */
    class Incomplete(val rxId: Int, val received: Int, val expected: Int, val command: String) :
        ObdException("Incomplete reply from ${ObdHex.canId(rxId)} to $command: $received of $expected bytes")

    /**
     * The ECU answered `7F <sid> <nrc>`. [nrcName] is a readable name such as "service not supported".
     * Also available as [ObdNegativeResponse].
     */
    class NegativeResponse(val rxId: Int, val serviceId: Int, val nrc: Int) :
        ObdException("ECU ${ObdHex.canId(rxId)} refused service ${ObdHex.byte(serviceId)}: ${Nrc.name(nrc)} (${ObdHex.byte(nrc)})") {
        val nrcName: String get() = Nrc.name(nrc)
    }
}

/** Short alias for the negative-response exception. */
typealias ObdNegativeResponse = ObdException.NegativeResponse

/** Negative response codes (ISO 14229 UDS / ISO 14230 KWP2000) with readable names. */
object Nrc {
    const val GENERAL_REJECT = 0x10
    const val SERVICE_NOT_SUPPORTED = 0x11
    const val SUB_FUNCTION_NOT_SUPPORTED = 0x12
    const val BUSY_REPEAT_REQUEST = 0x21
    const val CONDITIONS_NOT_CORRECT = 0x22
    const val REQUEST_OUT_OF_RANGE = 0x31
    const val SECURITY_ACCESS_DENIED = 0x33
    const val RESPONSE_PENDING = 0x78

    private val names = mapOf(
        0x10 to "general reject",
        0x11 to "service not supported",
        0x12 to "sub-function not supported",
        0x13 to "incorrect message length or format",
        0x14 to "response too long",
        0x21 to "busy, repeat request",
        0x22 to "conditions not correct",
        0x24 to "request sequence error",
        0x25 to "no response from sub-net component",
        0x26 to "failure prevents execution",
        0x31 to "request out of range",
        0x33 to "security access denied",
        0x35 to "invalid key",
        0x36 to "exceeded number of attempts",
        0x37 to "required time delay not expired",
        0x70 to "upload/download not accepted",
        0x71 to "transfer data suspended",
        0x72 to "general programming failure",
        0x73 to "wrong block sequence counter",
        0x78 to "response pending",
        0x7E to "sub-function not supported in active session",
        0x7F to "service not supported in active session",
        0x80 to "service not supported in active diagnostic session",
        0x81 to "rpm too high",
        0x82 to "rpm too low",
        0x83 to "engine is running",
        0x84 to "engine is not running",
        0x85 to "engine run time too low",
        0x86 to "temperature too high",
        0x87 to "temperature too low",
        0x88 to "vehicle speed too high",
        0x89 to "vehicle speed too low",
        0x8A to "throttle/pedal too high",
        0x8B to "throttle/pedal too low",
        0x8C to "transmission not in neutral",
        0x8D to "transmission not in gear",
        0x8F to "brake switch not closed",
        0x90 to "shifter lever not in park",
        0x91 to "torque converter clutch locked",
        0x92 to "voltage too high",
        0x93 to "voltage too low",
    )

    /** Readable name for [code], e.g. `0x31` → "request out of range". */
    fun name(code: Int): String = names[code and 0xFF] ?: "negative response 0x${ObdHex.byte(code)}"
}
