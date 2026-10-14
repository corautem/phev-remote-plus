package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.diag.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge gets itself back on the car's Wi-Fi when NetworkManager gave up (field log: off all night). */
class CarWifiWatchTest {
    private val log = DiagLog()
    private var t = 1_000_000_000L

    /** A bridge with the car's network saved on wlan0; [onCar] says whether it is on it right now. */
    private inner class Pi {
        var onCar = true
        var configured = true
        var carInSight: Int? = 60
        var resumeJoins = false
        val calls = ArrayList<String>()

        val runner = CarWifi.Runner { argv, _, _ ->
            val cmd = argv.joinToString(" ")
            calls += cmd
            when {
                cmd.startsWith("nmcli -t -f DEVICE,TYPE,STATE,CONNECTION device status") -> CarWifi.Run(
                    0, "eth0:ethernet:connected:w\n" + if (onCar) "wlan0:wifi:connected:phev-car\n" else "wlan0:wifi:disconnected:\n",
                )
                cmd.startsWith("ip route show default") -> CarWifi.Run(0, "default via 192.168.1.1 dev eth0\n")
                cmd.startsWith("nmcli -g 802-11-wireless.ssid connection show phev-car") ->
                    if (configured) CarWifi.Run(0, "REMOTE46pdmp\n") else CarWifi.Run(10, "Error: no such connection profile.")
                cmd.startsWith("nmcli -g IP4.ADDRESS device show wlan0") -> CarWifi.Run(0, "192.168.8.47/24\n")
                cmd.startsWith("nmcli -t -f IN-USE,SIGNAL device wifi list ifname wlan0") -> CarWifi.Run(0, "*:55\n")
                cmd.startsWith("nmcli -t -f SSID,SIGNAL device wifi list ifname wlan0 --rescan no") ->
                    CarWifi.Run(0, "Neighbour:80\n" + (carInSight?.let { "REMOTE46pdmp:$it\n" } ?: ""))
                cmd == "sudo -n /opt/phev-bridge/carwifi.sh resume wlan0" -> if (resumeJoins) {
                    onCar = true
                    CarWifi.Run(0, "@resumed\n@up-ok\n")
                } else CarWifi.Run(0, "@resumed\n@up-failed Error: Connection activation failed: Secrets were required, but not provided.\n")
                cmd == "sudo -n /opt/phev-bridge/carwifi.sh scan wlan0" ->
                    CarWifi.Run(0, "Neighbour:80:WPA2\n" + (carInSight?.let { "REMOTE46pdmp:$it:WPA2\n" } ?: ""))
                cmd == "sudo -n /opt/phev-bridge/carwifi.sh pause wlan0" -> {
                    onCar = false
                    CarWifi.Run(0, "@paused\n")
                }
                cmd == "sudo -n /opt/phev-bridge/carwifi.sh why wlan0" ->
                    CarWifi.Run(0, "@nm 2026-10-13T01:06:12 pi NetworkManager[512]: <info> device (wlan0): state change: activated -> failed (reason 'no-secrets')\n@done\n")
                else -> CarWifi.Run(10, "unexpected")
            }
        }
        val wifi = CarWifi("/opt/phev-bridge/carwifi.sh", "", log, runner, { null }, { true })
        fun resumes() = calls.count { it.endsWith("carwifi.sh resume wlan0") }
    }

    private val activity = ArrayList<Activity>()
    private var backs = 0

    private fun watch(pi: Pi) = CarWifiWatch(pi.wifi, log, onActivity = { activity += it }, onBack = { backs++ }, now = { t })

    private fun minutes(m: Double) {
        t += (m * 60_000).toLong()
    }

    private fun logText() = log.dump()

    @Test
    fun getsBackOnAfterNetworkManagerGaveUp() {
        val pi = Pi()
        val w = watch(pi)
        assertTrue(w.check().connected)
        minutes(300.0)
        assertTrue(w.check().connected)

        // A phone took the car's Wi-Fi; NetworkManager gave up ("no-secrets").
        pi.onCar = false
        minutes(1.0)
        val first = w.check()
        assertFalse(first.connected)
        assertFalse("NetworkManager gets a minute to try by itself", first.tried)
        assertTrue(logText(), logText().contains("lost the car's Wi-Fi on wlan0 after 5 h 1 min"))
        assertTrue("NetworkManager's own words are logged", logText().contains("reason 'no-secrets'"))

        minutes(1.0)
        val r = w.check()
        assertTrue(r.tried)
        assertFalse(r.connected)
        assertEquals(1, pi.resumes())
        minutes(0.5) // backs off: 1, 2, 4, 8, then every 10 minutes
        assertFalse(w.check().tried)
        assertEquals(1, pi.resumes())

        minutes(1.5) // 3 min off: worth a line in the activity log; the car is still in range
        assertTrue(w.check().tried)
        assertEquals(2, pi.resumes())
        val lost = activity.single { it.code == "bridge.carwifi_lost" }
        assertEquals(true, lost.bool("inSight"))
        assertTrue(ActivityText.english(lost), ActivityText.english(lost).contains("still in range"))

        pi.resumeJoins = true
        minutes(4.0)
        val back = w.check()
        assertTrue(back.connected)
        assertTrue(back.tried)
        assertEquals(1, backs)
        val b = activity.single { it.code == "bridge.carwifi_back" }
        assertEquals(7, b.int("minutes"))
        assertTrue(logText(), logText().contains("back on the car's Wi-Fi after 7 min (signal 55 %, after 3 tries by the watch)"))
    }

    @Test
    fun backsOffWhileTheCarIsAwayAndJoinsAsSoonAsItIsInSight() {
        val pi = Pi().apply { onCar = false; carInSight = null }
        val w = watch(pi)
        assertTrue("first look after starting: try at once", w.check().tried)
        val gaps = ArrayList<Int>()
        var last = 0
        repeat(60) {
            minutes(1.0)
            if (w.check().tried) { gaps += it + 1 - last; last = it + 1 }
        }
        assertEquals(listOf(1, 2, 4, 8, 10, 10, 10), gaps.take(7))
        // The car comes home: in sight → join now, not at the next 10-minute try.
        pi.carInSight = 70
        pi.resumeJoins = true
        minutes(1.0)
        val r = w.check()
        assertTrue(r.tried)
        assertTrue(r.connected)
        assertTrue(logText().contains("in sight again"))
        // Car came home after a long time away: logged as "back" (there was no "lost": the bridge started without it).
        assertEquals(listOf("bridge.carwifi_back"), activity.map { it.code })
    }

    @Test
    fun aPhoneCanAskForATryRightAway() {
        val pi = Pi().apply { onCar = false }
        val w = watch(pi)
        w.check()
        assertEquals(1, pi.resumes())
        minutes(0.2)
        assertFalse(w.check().tried)
        val r = w.check(rejoinNow = true, who = "Pixel 8")
        assertTrue(r.tried)
        assertEquals(2, pi.resumes())
        assertTrue(logText().contains("for Pixel 8"))
        assertEquals(60, r.signal)
    }

    @Test
    fun leavesTheCarsWifiAloneWhilePausedOrNotSetUp() {
        val pi = Pi().apply { onCar = false; configured = false }
        val w = watch(pi)
        assertFalse(w.check().tried)
        assertFalse(w.check(rejoinNow = true).tried)
        assertEquals(0, pi.resumes())

        val paused = Pi()
        val pw = watch(paused)
        assertTrue(pw.check().connected)
        // Pause (a phone registers with the car): the helper leaves the Wi-Fi; the watch must not pull it back.
        assertEquals(null, paused.wifi.pause(10))
        assertFalse(paused.onCar)
        minutes(5.0)
        assertFalse(pw.check().tried)
        val r = pw.check(rejoinNow = true)
        assertFalse(r.tried)
        assertEquals(0, paused.resumes())
    }

    @Test
    fun backoffSteps() {
        assertEquals(listOf(1L, 2, 4, 8, 10, 10), (1..6).map { CarWifiWatch.backoffMs(it) / 60_000 })
    }

    @Test
    fun aPhoneIsNotKeptWaitingWhenTheCarIsAway() {
        val pi = Pi().apply { onCar = false; carInSight = null }
        val w = watch(pi)
        w.check() // first look: one blind try
        assertEquals(1, pi.resumes())
        val r = w.check(rejoinNow = true, who = "Pixel 8")
        assertFalse(r.tried)
        assertFalse(r.connected)
        assertEquals(null, r.signal)
        assertEquals("looked with a fresh scan instead of a 30-second join", 1, pi.resumes())
        assertTrue(pi.calls.any { it.endsWith("carwifi.sh scan wlan0") })
        assertTrue(logText().contains("isn't in sight"))
    }

    /** What the phones show: in reach / checking / not seen since …, pushed on every change. */
    @Test
    fun reachSaysWhenTheCarIsNotSeen() {
        val pi = Pi()
        val w = watch(pi)
        var pushes = 0
        w.onReachChange = { pushes++ }
        w.check()
        assertEquals("in_reach", w.reachState)
        val seenAt = t
        assertEquals(seenAt, w.reach()["lastSeenMs"])

        // The car drives off: first just "checking" (could be a moment), then not seen once a real scan says so.
        pi.onCar = false
        pi.carInSight = null
        minutes(1.0)
        w.check()
        assertEquals("checking", w.reachState)
        assertTrue("looked for real", pi.calls.any { it.endsWith("carwifi.sh scan wlan0") })
        minutes(1.0)
        w.check()
        assertEquals("off for only a minute", "checking", w.reachState)
        minutes(1.0)
        w.check()
        assertEquals("not_seen", w.reachState)
        assertEquals("last seen stays when the car left", seenAt, w.reach()["lastSeenMs"])
        assertTrue(logText(), logText().contains("Reach: checking → not_seen"))

        // Hours later, still away: a real scan at most every 5 minutes, still not seen.
        val scansBefore = pi.calls.count { it.endsWith("carwifi.sh scan wlan0") }
        repeat(10) { minutes(1.0); w.check() }
        val scans = pi.calls.count { it.endsWith("carwifi.sh scan wlan0") } - scansBefore
        assertTrue("scans: $scans", scans in 1..3)
        assertEquals("not_seen", w.reachState)

        // Home again: in sight → checking (joining) → in reach.
        pi.carInSight = 70
        pi.resumeJoins = true
        minutes(1.0)
        w.check()
        assertEquals("in_reach", w.reachState)
        assertTrue(pushes >= 4)
        assertEquals(t, w.reach()["lastSeenMs"])
    }

    @Test
    fun aBridgeStartedWithoutTheCarSaysNotSeenWithTheLastContact() {
        val pi = Pi().apply { onCar = false; carInSight = null }
        val w = CarWifiWatch(pi.wifi, log, now = { t }, lastContact = { 123L })
        w.check()
        assertEquals("checking", w.reachState)
        assertEquals(123L, w.reach()["lastSeenMs"])
        minutes(2.0)
        w.check()
        assertEquals("not_seen", w.reachState)
    }

    @Test
    fun steppingAsideForAPhoneIsItsOwnState() {
        val pi = Pi()
        val w = watch(pi)
        w.check()
        pi.wifi.pause(10)
        w.check()
        assertEquals("stepped_aside", w.reachState)
    }
}
