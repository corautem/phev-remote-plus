package io.github.anwo91.phevlink.core.obd.dtc

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostic trouble code (DTC) lookup: titles for ~3500 generic OBD-II codes plus a plain-English
 * (or Norwegian, Swedish, Danish, German) explanation — system, severity, likely causes, what to
 * check, drive advice — for any valid code.
 *
 * English titles come from codes.tsv (generated from AndrOBD and python-OBD, see THIRD_PARTY_NOTICES.md),
 * translated titles from codes_<lang>.tsv. Explanations come from, in order:
 *  1. hand-written entries for codes an Outlander PHEV owner is likely to see ([curatedCodes]),
 *  2. rules based on the code number and the wording of the English title,
 *  3. a generic "manufacturer-specific / unknown code" explanation.
 * All text is in strings_<lang>.txt; anything not translated falls back to English for that piece.
 *
 * Every `lang` parameter accepts one of [languages] or any BCP-47 tag (see [languageFor]).
 * Thread-safe. Never throws for a syntactically valid code, and never crashes if a resource is missing.
 */
object DtcDatabase {

    private const val RESOURCE = "/io/github/anwo91/phevlink/core/obd/dtc/codes.tsv"
    private val CODE = Regex("^[PCBU][0-9A-F]{4}$")
    private val WHITESPACE = Regex("\\s+")

    /** Supported languages: English, Norwegian bokmål, Swedish, Danish, German. */
    val languages: List<String> = DtcI18n.LANGUAGES

    /** Maps a BCP-47 tag ("nb", "nn", "no", "nb-NO", "sv-SE", "da-DK", "de-AT", ...) to one of [languages]; default "en". */
    fun languageFor(tag: String): String = DtcI18n.languageFor(tag)

    private class Data(val titles: Map<String, String>, val error: String?) {
        /** All codes, sorted. */
        val sorted: List<String> = titles.keys.sorted()

        /** Search order for title matches: curated codes first, then the rest, each sorted. */
        val searchOrder: List<String> = sorted.filter { it in CuratedDtcs.codes } + sorted.filter { it !in CuratedDtcs.codes }
    }

    /** Per-language titles and search text. */
    private class LangData(val titles: Map<String, String>, val haystack: Map<String, String>)

    private val data: Data by lazy { load() }
    private val langData = ConcurrentHashMap<String, LangData>()

    private fun langData(lang: String): LangData = langData.getOrPut(lang) {
        val d = data
        val local = if (lang == DtcI18n.EN) emptyMap() else DtcI18n.readTable("codes_$lang.tsv")
        val titles = d.titles.mapValues { (code, en) -> local[code] ?: en }
        val haystack = d.titles.mapValues { (code, en) ->
            buildString {
                append(en)
                local[code]?.let { append(' ').append(it) }
                CuratedDtcs[code]?.let { def ->
                    append(' ').append(DtcI18n.text(DtcI18n.EN, def.sub, def.args))
                    if (lang != DtcI18n.EN) append(' ').append(DtcI18n.text(lang, def.sub, def.args))
                }
            }.lowercase(Locale.ROOT)
        }
        LangData(titles, haystack)
    }

    /** Number of codes with a known title. */
    val size: Int get() = data.titles.size

    /** A problem found while loading codes.tsv or strings_en.txt (missing, unreadable or malformed lines), or null. */
    val loadError: String?
        get() {
            DtcI18n.strings(DtcI18n.EN)
            return data.error ?: DtcI18n.loadProblems()["strings_en.txt"]
        }

    /** Codes that have a hand-written (curated) explanation, sorted. */
    val curatedCodes: Set<String> by lazy { java.util.Collections.unmodifiableSet(CuratedDtcs.codes.toSortedSet()) }

    /** Upper-cases and trims a code: " p0a80 " -> "P0A80". Does not validate. */
    fun normalize(code: String): String = code.trim().uppercase(Locale.ROOT)

    /** True for P/C/B/U followed by exactly 4 hex digits (after [normalize]). */
    fun isValid(code: String): Boolean = CODE.matches(normalize(code))

    /** Title in [lang] (English when not translated), null when the code is unknown. Accepts "p0a80", " P0A80 ". */
    fun title(code: String, lang: String = "en"): String? {
        val c = normalize(code)
        val l = languageFor(lang)
        return if (l == DtcI18n.EN) data.titles[c] else langData(l).titles[c]
    }

    /**
     * Full explanation in [lang] for any syntactically valid code (P/C/B/U + 4 hex digits).
     * @throws IllegalArgumentException for invalid syntax.
     */
    fun explain(code: String, lang: String = "en"): DtcExplanation {
        val c = normalize(code)
        require(CODE.matches(c)) {
            "Not a valid trouble code: '$code' (expected P, C, B or U followed by 4 hex digits, e.g. P0A80)"
        }
        val l = languageFor(lang)
        val enTitle = data.titles[c]
        val localTitle = if (enTitle == null) null else title(c, l)
        CuratedDtcs[c]?.let { return CuratedDtcs.explain(c, it, l, localTitle) }
        return DtcRules.explain(c, enTitle, l, localTitle)
    }

    /**
     * Search titles and codes, for a lookup screen. Case-insensitive, at most [limit] results.
     * Matches titles in [lang] and in English. Order: exact code match, then codes starting with the
     * query, then title matches (all words must appear; curated codes first). Pairs are (code, title in [lang]).
     */
    fun search(query: String, limit: Int = 50, lang: String = "en"): List<Pair<String, String>> {
        if (limit <= 0) return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val l = languageFor(lang)
        val d = data
        val ld = langData(l)
        val upper = q.uppercase(Locale.ROOT)
        val out = LinkedHashMap<String, String>()

        if (CODE.matches(upper)) out[upper] = ld.titles[upper] ?: explain(upper, l).title

        if (upper.length <= 5 && upper.all { it.isLetterOrDigit() }) {
            for (code in d.sorted) {
                if (out.size >= limit) break
                if (code.startsWith(upper)) out.putIfAbsent(code, ld.titles.getValue(code))
            }
        }

        if (out.size < limit) {
            val words = q.lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }
            for (code in d.searchOrder) {
                if (out.size >= limit) break
                if (code in out) continue
                val text = ld.haystack.getValue(code)
                if (words.all { text.contains(it) }) out[code] = ld.titles.getValue(code)
            }
        }
        return out.entries.take(limit).map { it.key to it.value }
    }

    private fun load(): Data {
        val stream = try {
            DtcDatabase::class.java.getResourceAsStream(RESOURCE)
        } catch (e: Exception) {
            return Data(emptyMap(), "Could not open $RESOURCE: $e")
        } ?: return Data(emptyMap(), "Resource $RESOURCE not found on the classpath")

        return try {
            val map = HashMap<String, String>(4096)
            var skipped = 0
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.removePrefix("﻿")
                    if (line.isBlank() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    val code = if (tab > 0) normalize(line.substring(0, tab)) else ""
                    val title = if (tab > 0) line.substring(tab + 1).trim() else ""
                    if (CODE.matches(code) && title.isNotEmpty()) map[code] = title else skipped++
                }
            }
            Data(map, if (skipped > 0) "Skipped $skipped malformed lines in $RESOURCE" else null)
        } catch (e: Exception) {
            Data(emptyMap(), "Could not read $RESOURCE: $e")
        }
    }
}
