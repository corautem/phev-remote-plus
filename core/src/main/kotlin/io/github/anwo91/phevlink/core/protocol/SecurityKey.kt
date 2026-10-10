package io.github.anwo91.phevlink.core.protocol

/**
 * Rolling XOR "security" used by the car once a session is initialised.
 *
 * The car sends a start packet (0x4e on 2014-17 cars, 0x5e on 2018+) whose
 * payload seeds a single-byte security key. That key drives an RC4-style
 * key-scheduling pass that produces a 256-entry permutation (the key map).
 * Every packet we send is XORed with keyMap[sNum]; sNum advances after each
 * 0xf6 packet we send. The algorithm was reverse engineered by @zivillian
 * (see phev2mqtt issue #16).
 *
 * Addition over the reference: [resyncSend] re-aligns our send index when the
 * car reports the key it expected (0xbb packet), instead of guessing.
 */
class SecurityKey {
    var keyMap: IntArray? = null
        private set
    var securityKey: Int = 0
        private set
    var sendIndex: Int = 0
        private set
    var recvIndex: Int = 0
        private set

    /** Key to force on the next send when no key map exists yet (from a 0xbb hint). */
    private var overrideXor: Int? = null

    val isEstablished: Boolean get() = keyMap != null

    /** Seed from the full plain bytes of a start packet (header included). */
    fun update(startPacketBytes: ByteArray) {
        overrideXor = null
        if (startPacketBytes.size < 12) {
            keyMap = null
            securityKey = 0
            sendIndex = 0
            recvIndex = 0
            return
        }
        val p = startPacketBytes
        var result = (p[4].toInt() and 0x8) shr 3
        result = result or ((p[5].toInt() and 0x8) shr 2)
        result = result or ((p[6].toInt() and 0x8) shr 1)
        result = result or (p[7].toInt() and 0x8)
        result = result or ((p[8].toInt() and 0x8) shl 1)
        result = result or ((p[9].toInt() and 0x8) shl 2)
        result = result or ((p[10].toInt() and 0x8) shl 3)
        result = result or ((p[11].toInt() and 0x8) shl 4)
        securityKey = result and 0xff

        val map = IntArray(256) { it }
        var index = 0
        for (i in 0 until 256) {
            index = (index + map[i] + securityKey) % 256
            val tmp = map[i]
            map[i] = map[index]
            map[index] = tmp
        }
        keyMap = map
        sendIndex = 0
        recvIndex = 0
    }

    fun clear() = update(ByteArray(0))

    /** XOR key for the next outgoing packet. [advance] = true for 0xf6 packets. */
    fun sendKey(advance: Boolean): Int {
        overrideXor?.let { forced ->
            overrideXor = null
            return forced
        }
        val map = keyMap ?: return 0
        val key = map[sendIndex]
        if (advance) sendIndex = (sendIndex + 1) and 0xff
        return key
    }

    /** The key the next 0xf6 packet would use, without consuming anything (diagnostics). */
    fun peekSendKey(): Int {
        overrideXor?.let { return it }
        val map = keyMap ?: return 0
        return map[sendIndex]
    }

    /** Expected key of the next incoming packet (diagnostics only; decoding self-detects). */
    fun recvKey(advance: Boolean): Int {
        val map = keyMap ?: return 0
        val key = map[recvIndex]
        if (advance) recvIndex = (recvIndex + 1) and 0xff
        return key
    }

    /**
     * The car told us which key it expected. Point the send index at that key
     * so the retried packet uses it. Returns true when the key was found.
     */
    fun resyncSend(expectedKey: Int): Boolean {
        val map = keyMap
        if (map == null) {
            overrideXor = expectedKey and 0xff
            return false
        }
        val idx = map.indexOf(expectedKey and 0xff)
        if (idx < 0) return false
        sendIndex = idx
        return true
    }
}
