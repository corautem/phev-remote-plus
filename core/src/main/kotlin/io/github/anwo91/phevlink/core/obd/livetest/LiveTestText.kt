package io.github.anwo91.phevlink.core.obd.livetest

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Texts for the live tests in English, Norwegian bokmål, Swedish, Danish and German.
 *
 * Resources `strings_<lang>.txt` next to this class (UTF-8): `key<TAB>text`, `#` comments. Texts can contain
 * `{name}` slots filled from [Msg.args]. Missing keys fall back to English, then to the key itself.
 */
object LiveTestText {
    val LANGUAGES = listOf("en", "nb", "sv", "da", "de")
    private const val DIR = "/io/github/anwo91/phevlink/core/obd/livetest/"
    private val SLOT = Regex("""\{([A-Za-z0-9_]+)\}""")
    private val tables = ConcurrentHashMap<String, Map<String, String>>()

    fun languageFor(tag: String?): String {
        val primary = tag?.trim()?.lowercase(Locale.ROOT)?.split('-', '_')?.firstOrNull().orEmpty()
        return when (primary) {
            "nb", "nn", "no" -> "nb"
            "sv", "da", "de" -> primary
            else -> "en"
        }
    }

    fun locale(lang: String): Locale = when (lang) {
        "nb" -> Locale.forLanguageTag("nb-NO")
        "sv" -> Locale.forLanguageTag("sv-SE")
        "da" -> Locale.forLanguageTag("da-DK")
        "de" -> Locale.forLanguageTag("de-DE")
        else -> Locale.UK
    }

    internal fun table(lang: String): Map<String, String> = tables.getOrPut(lang) { load(lang) }

    /** All keys of the English table (for consistency tests). */
    fun keys(): Set<String> = table("en").keys

    fun has(key: String, lang: String = "en"): Boolean = table(lang).containsKey(key)

    /** Resolves [msg] in [lang]. */
    fun text(msg: Msg, lang: String = "en"): String = resolve(msg, languageFor(lang), 0)

    fun text(key: String, lang: String = "en"): String = text(Msg(key), lang)

    /** Formats a number for [lang] (decimal comma where usual). */
    fun number(value: Double, decimals: Int, lang: String): String {
        val s = String.format(locale(languageFor(lang)), "%.${decimals.coerceIn(0, 6)}f", value)
        return if (s.startsWith("-") && s.trimStart('-').all { it == '0' || it == ',' || it == '.' }) s.substring(1) else s
    }

    /** "12.62 V", "85 %", "—" for a [Measurement] value. */
    fun value(m: Measurement, lang: String): String {
        m.text?.let { return text(it, lang) }
        val v = m.value ?: return "—"
        val num = number(v, m.decimals, lang)
        return if (m.unit.isEmpty()) num else "$num ${m.unit}"
    }

    private fun resolve(msg: Msg, lang: String, depth: Int): String {
        val template = table(lang)[msg.key] ?: table("en")[msg.key] ?: return msg.key
        if (depth > 4) return template
        return SLOT.replace(template) { m ->
            when (val arg = msg.args[m.groupValues[1]]) {
                null -> ""
                is Num -> number(arg.value, arg.decimals, lang)
                is Double -> number(arg, 1, lang)
                is Float -> number(arg.toDouble(), 1, lang)
                is Msg -> resolve(arg, lang, depth + 1)
                else -> arg.toString()
            }
        }.replace("  ", " ").trim()
    }

    private fun load(lang: String): Map<String, String> {
        val stream = LiveTestText::class.java.getResourceAsStream("${DIR}strings_$lang.txt") ?: return emptyMap()
        return try {
            val map = HashMap<String, String>(512)
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.removePrefix("﻿")
                    if (line.isBlank() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val value = line.substring(tab + 1).trim().replace("\\n", "\n")
                    if (value.isNotEmpty()) map[line.substring(0, tab).trim()] = value
                }
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
