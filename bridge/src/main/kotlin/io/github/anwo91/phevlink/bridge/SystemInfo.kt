package io.github.anwo91.phevlink.bridge

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** A few facts about the computer the bridge runs on, for the app's bridge page. Missing facts are simply left out. */
object SystemInfo {
    fun read(root: File = File("/")): Map<String, Any?> = linkedMapOf(
        "hostname" to text(root, "etc/hostname")?.trim(),
        "model" to text(root, "proc/device-tree/model")?.trim('\u0000', ' ', '\n'),
        "os" to text(root, "etc/os-release")?.lineSequence()
            ?.firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"'),
        "uptimeS" to text(root, "proc/uptime")?.substringBefore(' ')?.toDoubleOrNull()?.toLong(),
        "cpuTempC" to text(root, "sys/class/thermal/thermal_zone0/temp")?.trim()?.toDoubleOrNull()?.let { Math.round(it / 100.0) / 10.0 },
        "load" to text(root, "proc/loadavg")?.substringBefore(' ')?.toDoubleOrNull(),
        "memTotalMb" to meminfo(root, "MemTotal"),
        "memAvailableMb" to meminfo(root, "MemAvailable"),
        "diskFreeMb" to runCatching { root.usableSpace / (1024 * 1024) }.getOrNull(),
        "java" to System.getProperty("java.version"),
        "addresses" to addresses(),
    )

    private fun text(root: File, path: String): String? = runCatching { File(root, path).readText() }.getOrNull()

    private fun meminfo(root: File, key: String): Long? =
        text(root, "proc/meminfo")?.lineSequence()?.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.div(1024)

    /** IPv4 address per network interface ("eth0" → "192.168.1.21"), loopback left out. */
    private fun addresses(): Map<String, String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .mapNotNull { nif -> nif.inetAddresses.toList().firstOrNull { it is Inet4Address }?.let { nif.name to it.hostAddress } }
            .toMap()
    }.getOrDefault(emptyMap())
}
