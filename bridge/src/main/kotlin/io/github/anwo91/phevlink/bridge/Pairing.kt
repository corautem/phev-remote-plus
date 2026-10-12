package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import java.io.File

/**
 * "Re-plug to pair": for [minutes] after the computer is powered on, the app may fetch the API token once without
 * knowing it. Unplugging the bridge and plugging it back in is the proof of physical access — the same idea as a
 * pairing button on a device without buttons.
 *
 * - The window is measured from **system boot** (/proc/uptime), not from when this process started, so the bridge
 *   restarting itself (crash, settings change) never reopens it.
 * - It closes after the first successful pairing **of this boot**, remembered in [stateDir] by the kernel's boot id,
 *   so it stays closed across process restarts too.
 * - An already paired phone can also open it for one more phone ([invite], "Add another phone" in the app — the
 *   request needs the token): for a few minutes, one pairing, not remembered across restarts.
 *
 * @param uptimeSeconds seconds since boot, or null when unknown (then pairing is closed)
 * @param bootId an id that changes on every boot, or null when unknown (then pairing is closed)
 */
class Pairing(
    private val minutes: Int,
    private val stateDir: File?,
    private val log: DiagLog,
    private val uptimeSeconds: () -> Double? = ::systemUptime,
    private val bootId: () -> String? = ::systemBootId,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Whether pairing is possible now; [reason] says why not ("disabled", "unknown", "expired", "used"). */
    data class Window(val open: Boolean, val secondsLeft: Int, val reason: String?)

    @Volatile private var usedBoot: String? = null
    @Volatile private var inviteUntilMs = 0L
    @Volatile private var invitedBy: String? = null

    /** Opens pairing for one more phone for [minutes] (1–15), asked by an already paired phone. Returns seconds open. */
    @Synchronized
    fun invite(minutes: Int, by: String): Int {
        val m = minutes.coerceIn(1, 15)
        inviteUntilMs = now() + m * 60_000L
        invitedBy = by
        log.link("Pairing opened for $m min by $by (\"Add another phone\") — one phone can pair")
        return m * 60
    }

    private val marker: File? get() = stateDir?.let { File(it, "paired-boot") }

    fun window(): Window {
        val invited = ((inviteUntilMs - now()) / 1000).toInt()
        if (invited > 0) return Window(true, invited, null)
        if (minutes <= 0) return Window(false, 0, "disabled")
        val up = uptimeSeconds() ?: return Window(false, 0, "unknown")
        val boot = bootId() ?: return Window(false, 0, "unknown")
        val left = (minutes * 60 - up).toInt()
        if (left <= 0) return Window(false, 0, "expired")
        if (boot == usedBoot || boot == readMarker()) return Window(false, 0, "used")
        return Window(true, left, null)
    }

    /**
     * Claims this boot's pairing for [who] (logged). Returns true when the window was open — the caller then hands
     * out the token; false when it is closed (see [window]).
     */
    @Synchronized
    fun claim(who: String): Boolean {
        val w = window()
        if (!w.open) {
            log.warn("Pairing refused for $who: ${w.reason}")
            return false
        }
        if (inviteUntilMs > now()) {
            inviteUntilMs = 0L
            log.link("Paired with $who (invited by ${invitedBy ?: "?"}) — pairing closed again")
            return true
        }
        val boot = bootId() ?: return false
        usedBoot = boot
        val saved = runCatching {
            val f = marker ?: error("no state directory")
            f.absoluteFile.parentFile?.mkdirs()
            f.writeText(boot)
        }
        if (saved.isFailure) {
            // Still closed for the life of this process; only a restart within the window could reopen it.
            log.warn("Pairing: could not remember this boot (${saved.exceptionOrNull()?.message}) — closed until the bridge restarts")
        }
        log.link("Paired with $who — pairing closed until the bridge is next powered on")
        return true
    }

    private fun readMarker(): String? = runCatching { marker?.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()

    companion object {
        fun systemUptime(): Double? =
            runCatching { File("/proc/uptime").readText().trim().split(' ').first().toDouble() }.getOrNull()

        fun systemBootId(): String? =
            runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim().ifEmpty { null } }.getOrNull()
    }
}
