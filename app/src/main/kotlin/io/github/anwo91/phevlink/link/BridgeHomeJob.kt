package io.github.anwo91.phevlink.link

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.anwo91.phevlink.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * While a bridge setup waits for the car ([io.github.anwo91.phevlink.data.AppSettings.bridgeSetupPending]), asks the
 * bridge every ~15 minutes whether it has joined the car's Wi-Fi — i.e. the car is home — and notifies once per
 * arrival so the user can register the bridge. Stops itself when nothing is pending any more. Every check is logged.
 */
class BridgeHomeJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = applicationContext.graph.scope.launch {
            try {
                check(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true // run again later
    }

    companion object {
        private const val JOB_ID = 0x7B1D
        private const val PREFS = "bridge_home"
        private const val KEY_CAR_HOME = "car_home"
        private const val PERIOD_MS = 15 * 60_000L

        /** Starts the periodic check (survives reboots). Safe to call again. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, BridgeHomeJob::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            val r = js.schedule(info)
            context.graph.log.link("Bridge check: background check every 15 min " + if (r == JobScheduler.RESULT_SUCCESS) "scheduled" else "could NOT be scheduled")
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CAR_HOME).apply()
        }

        /**
         * One check: notify when the bridge has just joined the car's Wi-Fi (car arrived), remember it for next time.
         * [notify] = false when the app itself asks (it shows the result on screen). Returns what the bridge reported,
         * or null when nothing is pending or the bridge didn't answer.
         */
        suspend fun check(context: Context, notify: Boolean = true): BridgeWifi? {
            val g = context.graph
            val s = g.settings.current
            val who = if (notify) "Bridge check" else "Bridge check (app open)"
            if (!s.bridgeSetupPending || !s.hasBridge || s.bridgeToken.isBlank()) {
                g.log.link("$who: nothing pending — stopping the background check")
                cancel(context)
                return null
            }
            val r = BridgeSetupApi.carWifi(s.bridgeUrl, s.bridgeToken)
            if (r == null || r.code != 200) {
                g.log.warn("$who: bridge at ${s.bridgeUrl} not reachable (${r?.code ?: "no answer"}) — trying again later")
                return null
            }
            val info = BridgeWifi.from(r.body)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val wasHome = prefs.getBoolean(KEY_CAR_HOME, false)
            g.log.link(
                "$who: bridge ${if (info.connected) "is on the car's Wi-Fi (car home)" else "doesn't see the car"}" +
                    (info.signal?.let { ", signal $it%" } ?: "") + "; last check: ${if (wasHome) "home" else "away"}",
            )
            if (info.connected && !wasHome && notify) g.notifier.bridgeCarHome()
            prefs.edit().putBoolean(KEY_CAR_HOME, info.connected).apply()
            return info
        }
    }
}
