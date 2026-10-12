package io.github.anwo91.phevlink.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Updates the app from inside the app: asks GitHub for the newest release, downloads its APK, checks that it is this
 * app signed with the same key and newer, then hands it to Android's installer (the first time, Android asks to allow
 * installs from PHEV Link). Every step is logged.
 */
class AppUpdater(private val context: Context, private val log: DiagLog, private val scope: CoroutineScope) {
    enum class Phase { IDLE, CHECKING, DOWNLOADING, READY, NEEDS_PERMISSION, FAILED }

    data class State(
        val current: String,
        val currentCode: Long,
        val latest: String? = null,
        val latestCode: Long? = null,
        val apkUrl: String? = null,
        val apkSize: Long? = null,
        val checkedAtMs: Long? = null,
        /** The last check could not reach GitHub. */
        val checkFailed: Boolean = false,
        val phase: Phase = Phase.IDLE,
        /** Download progress 0..1, null when the size is unknown. */
        val progress: Float? = null,
        val error: String? = null,
    ) {
        val available: Boolean get() = latestCode != null && latestCode > currentCode
    }

    private val pm = context.packageManager
    private val installed = pm.getPackageInfo(context.packageName, 0)
    private val _state = MutableStateFlow(State(installed.versionName ?: "?", installed.longVersionCode))
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private val apkDir get() = File(context.cacheDir, "updates")

    /** Checks at most every few hours (app start); [check] with force for the button. */
    fun checkIfDue() {
        val last = _state.value.checkedAtMs ?: 0L
        if (System.currentTimeMillis() - last > CHECK_EVERY_MS) check()
    }

    fun check() {
        if (job?.isActive == true) return
        _state.update { it.copy(phase = Phase.CHECKING, error = null) }
        job = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
            val rel = r.getOrNull()
            if (rel == null) {
                log.warn("App update: couldn't ask GitHub for the newest version (${r.exceptionOrNull()?.message ?: "no answer"})")
                _state.update { it.copy(phase = Phase.IDLE, checkFailed = true, checkedAtMs = System.currentTimeMillis()) }
                return@launch
            }
            log.link("App update: installed ${_state.value.current} (${_state.value.currentCode}), newest ${rel.version} (${rel.code})")
            _state.update {
                it.copy(
                    phase = Phase.IDLE, checkFailed = false, checkedAtMs = System.currentTimeMillis(),
                    latest = rel.version, latestCode = rel.code, apkUrl = rel.apkUrl, apkSize = rel.size,
                )
            }
        }
    }

    /** Downloads and checks the newest APK, then opens the installer from [activity]. */
    fun downloadAndInstall(activity: Context) {
        if (job?.isActive == true) return
        val s = _state.value
        val url = s.apkUrl ?: return check()
        _state.update { it.copy(phase = Phase.DOWNLOADING, progress = 0f, error = null) }
        job = scope.launch {
            val file = File(apkDir, "PHEV-Link-${s.latest}.apk")
            val result = withContext(Dispatchers.IO) { runCatching { download(url, file, s.apkSize) } }
            val why = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: verify(file, s.currentCode)
            if (why != null) {
                log.warn("App update: ${s.latest} not installed — $why")
                file.delete()
                _state.update { it.copy(phase = Phase.FAILED, error = why, progress = null) }
                return@launch
            }
            log.link("App update: ${s.latest} downloaded and checked (${file.length() / 1024} KB) — opening the installer")
            _state.update { it.copy(phase = Phase.READY, progress = 1f) }
            withContext(Dispatchers.Main) { install(activity) }
        }
    }

    /** Opens Android's installer for the downloaded APK (asks for the install permission first if needed). */
    fun install(activity: Context) {
        val file = File(apkDir, "PHEV-Link-${_state.value.latest}.apk")
        if (!file.exists()) return downloadAndInstall(activity)
        if (!pm.canRequestPackageInstalls()) {
            log.link("App update: asking for permission to install updates (Android setting for PHEV Link)")
            _state.update { it.copy(phase = Phase.NEEDS_PERMISSION) }
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            _state.update { it.copy(phase = Phase.READY) }
        }.onFailure {
            log.warn("App update: couldn't open the installer: ${it.message}")
            _state.update { s -> s.copy(phase = Phase.FAILED, error = it.message) }
        }
    }

    /** Back from Android's "install unknown apps" setting: continue if it was allowed. */
    fun onResume(activity: Context) {
        if (_state.value.phase == Phase.NEEDS_PERMISSION && pm.canRequestPackageInstalls()) install(activity)
    }

    // ------------------------------------------------------------------ helpers

    private data class Release(val version: String, val code: Long, val apkUrl: String, val size: Long?)

    private fun fetchLatest(): Release? {
        val c = URI("https://api.github.com/repos/$REPO/releases/latest").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "PHEVLink/${_state.value.current}")
        try {
            if (c.responseCode != 200) return null
            val m = Json.parseObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = (m["tag_name"] as? String)?.removePrefix("v") ?: return null
            val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
            val asset = (m["assets"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull { (it["name"] as? String)?.endsWith(".apk") == true } ?: return null
            val url = asset["browser_download_url"] as? String ?: return null
            return Release(tag, code, url, (asset["size"] as? Number)?.toLong())
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, file: File, expected: Long?) {
        file.parentFile?.mkdirs()
        apkDir.listFiles()?.filter { it != file }?.forEach { it.delete() } // old downloads
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "PHEVLink/${_state.value.current}")
        try {
            if (c.responseCode != 200) error("download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expected
            var done = 0L
            var lastReport = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null && done - lastReport > 128 * 1024) {
                            lastReport = done
                            _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                }
            }
            if (expected != null && done != expected) error("download incomplete ($done of $expected bytes)")
        } finally {
            c.disconnect()
        }
    }

    /** Null when [file] is this app, newer than [currentCode] and signed with the same key; else why not. */
    private fun verify(file: File, currentCode: Long): String? {
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return "the download is not an app"
        if (info.packageName != context.packageName) return "the download is a different app (${info.packageName})"
        if (info.longVersionCode <= currentCode) return "the download is not newer (${info.longVersionCode})"
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        val theirs = info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet()
        if (theirs == null) {
            log.warn("App update: couldn't read the download's signature — Android's installer checks it anyway")
        } else if (theirs != mine) {
            return "the download is signed with a different key"
        }
        return null
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        const val REPO = "Anwo91/phev-remote-plus"
        private const val CHECK_EVERY_MS = 6 * 3600_000L
    }
}
