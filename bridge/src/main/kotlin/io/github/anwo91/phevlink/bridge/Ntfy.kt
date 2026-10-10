package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** Sends push notifications through an ntfy topic (https://ntfy.sh or self-hosted). */
class Ntfy(private val url: String, private val token: String, private val log: DiagLog) {
    val enabled: Boolean get() = url.isNotBlank()

    suspend fun send(title: String, message: String, tag: String) {
        if (!enabled) return
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("Title", title)
                c.setRequestProperty("Tags", tagFor(tag))
                c.setRequestProperty("Priority", if (tag.startsWith("LEFT") || tag.startsWith("LIGHTS") || tag.startsWith("DOOR")) "high" else "default")
                if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
                c.outputStream.use { it.write(message.toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                if (code !in 200..299) log.warn("ntfy returned HTTP $code")
            }.onFailure { log.warn("ntfy failed: ${it.message}") }
        }
    }

    private fun tagFor(type: String) = when {
        type.startsWith("CHARGE") -> "electric_plug"
        type.startsWith("CLIMATE") -> "snowflake"
        type.startsWith("LEFT") -> "unlock"
        type.startsWith("LIGHTS") -> "bulb"
        type.startsWith("DOOR") -> "door"
        else -> "car"
    }
}
