package io.github.anwo91.phevlink.core.obd.dtc

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Localised text for the DTC explanations.
 *
 * Resources (UTF-8, one entry per line, `#` comments) in this package's resource folder:
 *  - `strings_<lang>.txt`: `key  text` (key, whitespace, text). `strings_en.txt` is the English source of truth;
 *    other languages translate the same keys. Values may contain `{name}` slots filled by the code,
 *    `{@other.key}` to include another string, or be just `@other.key` to reuse a whole string.
 *  - `components_<lang>.tsv`: `English phrase<TAB>translation` for component names extracted from English titles.
 *  - `codes.tsv` (English) and `codes_<lang>.tsv`: `CODE<TAB>title`.
 *
 * Anything missing in a language falls back to English for that piece only. Unknown slots are never
 * shown: a translation with a slot the code does not fill falls back to English, and an English text
 * with an unfilled slot has the slot removed.
 */
internal object DtcI18n {

    const val EN = "en"
    val LANGUAGES: List<String> = listOf("en", "nb", "sv", "da", "de")

    private const val DIR = "/io/github/anwo91/phevlink/core/obd/dtc/"
    private val SLOT = Regex("""\{(@?[A-Za-z0-9_.]+)\}""")

    private val strings = ConcurrentHashMap<String, Map<String, String>>()
    private val components = ConcurrentHashMap<String, Map<String, String>>()
    private val problems = ConcurrentHashMap<String, String>()

    /** Load problems (missing or unreadable files), for diagnostics. Empty when everything loaded. */
    fun loadProblems(): Map<String, String> = HashMap(problems)

    fun languageFor(tag: String?): String {
        val primary = tag?.trim()?.lowercase(Locale.ROOT)?.split('-', '_', '.', '@')?.firstOrNull().orEmpty()
        return when (primary) {
            "nb", "nn", "no" -> "nb"
            "sv" -> "sv"
            "da" -> "da"
            "de" -> "de"
            else -> EN
        }
    }

    // ---- resources -----------------------------------------------------------------------

    /**
     * Reads a `key<TAB>value` resource (or `key<whitespace>value` when [separator] is null, for the
     * strings files whose keys never contain spaces). Missing file -> empty map and a recorded problem.
     */
    fun readTable(file: String, required: Boolean = true, separator: Char? = '\t'): Map<String, String> {
        val stream = try {
            DtcI18n::class.java.getResourceAsStream(DIR + file)
        } catch (e: Exception) {
            problems[file] = "Could not open $file: $e"
            return emptyMap()
        }
        if (stream == null) {
            if (required) problems[file] = "Resource $file not found on the classpath"
            return emptyMap()
        }
        return try {
            val map = HashMap<String, String>(4096)
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.removePrefix("﻿")
                    if (line.isBlank() || line.startsWith("#")) continue
                    val sep = if (separator != null) line.indexOf(separator) else line.indexOfFirst { it == ' ' || it == '\t' }
                    if (sep <= 0) continue
                    val value = line.substring(sep + 1).trim()
                    if (value.isNotEmpty()) map[line.substring(0, sep).trim()] = value
                }
            }
            map
        } catch (e: Exception) {
            problems[file] = "Could not read $file: $e"
            emptyMap()
        }
    }

    fun strings(lang: String): Map<String, String> =
        strings.getOrPut(lang) { readTable("strings_$lang.txt", separator = null) }

    private fun componentTable(lang: String): Map<String, String> =
        components.getOrPut(lang) { if (lang == EN) emptyMap() else readTable("components_$lang.tsv") }

    /** Translation of an English component phrase, or the phrase itself when there is none. */
    fun component(lang: String, phrase: String): String =
        if (lang == EN || phrase.isEmpty()) phrase else componentTable(lang)[phrase] ?: phrase

    /** True when [lang] has its own translation of [phrase] (for coverage reports). */
    fun hasComponent(lang: String, phrase: String): Boolean = lang == EN || componentTable(lang).containsKey(phrase)

    // ---- arguments for slots -------------------------------------------------------------

    /** A value for a `{slot}`, resolved in the language of the text it is inserted into. */
    sealed interface Arg {
        fun resolve(lang: String): String
    }

    /** Fixed text (numbers, letters, codes). */
    class Lit(private val text: String) : Arg {
        override fun resolve(lang: String) = text
    }

    /** Another string from the table, optionally with its own slots. */
    class Key(private val key: String, private val args: Map<String, Arg> = emptyMap()) : Arg {
        override fun resolve(lang: String) = text(lang, key, args)
    }

    /** An English component phrase, translated through `components_<lang>.tsv`. */
    class Comp(private val phrase: String) : Arg {
        override fun resolve(lang: String) = component(lang, phrase)
    }

    // ---- rendering -----------------------------------------------------------------------

    /** The translation of [key] in [tag]'s language only (no English fallback), or null. */
    fun localOnly(tag: String, key: String): String? {
        val lang = languageFor(tag)
        return if (lang == EN) null else strings(lang)[key]
    }

    /** True when the English table defines [key]. */
    fun has(key: String): Boolean = strings(EN).containsKey(key)

    /**
     * The text for [key] in [lang], with slots filled from [args]. Falls back to English for this key
     * when the translation is missing or uses a slot that [args] does not provide. Never returns a raw key.
     */
    fun text(lang: String, key: String, args: Map<String, Arg> = emptyMap()): String = render(lang, key, args, 0)

    /** Numbered list `base1`, `base2`, ... as defined by the English table; each item rendered in [lang]. */
    fun list(lang: String, base: String, args: Map<String, Arg> = emptyMap()): List<String> {
        val en = strings(EN)
        val out = ArrayList<String>()
        var i = 1
        while (en.containsKey("$base$i")) {
            text(lang, "$base$i", args).takeIf { it.isNotBlank() }?.let { out += it }
            i++
        }
        return out
    }

    private fun render(lang: String, key: String, args: Map<String, Arg>, depth: Int): String {
        if (depth > 8) return ""
        if (lang != EN) {
            strings(lang)[key]?.let { v -> expand(v, lang, lang, args, depth, strict = true)?.let { return it } }
        }
        val en = strings(EN)[key] ?: return ""
        // English fallback for this piece; texts it references are still looked up in [lang].
        return expand(en, EN, lang, args, depth, strict = false) ?: ""
    }

    /**
     * Expands references (resolved in [refLang]) and slots (resolved in [textLang], the language of [value]).
     * Returns null (strict mode) when a slot cannot be filled, so the caller can fall back to English.
     */
    private fun expand(
        value: String,
        textLang: String,
        refLang: String,
        args: Map<String, Arg>,
        depth: Int,
        strict: Boolean,
    ): String? {
        if (value.startsWith("@") && !value.contains(' ')) return render(refLang, value.substring(1), args, depth + 1)
        if (!value.contains('{')) return value
        var missing = false
        val out = SLOT.replace(value) { m ->
            val name = m.groupValues[1]
            val filled = if (name.startsWith("@")) {
                render(refLang, name.substring(1), args, depth + 1)
            } else {
                args[name]?.resolve(textLang)
            }
            if (filled == null) {
                missing = true
                ""
            } else {
                filled
            }
        }
        if (missing && strict) return null
        return if (missing) out.replace(Regex(" {2,}"), " ").replace(" .", ".").trim() else out
    }
}
