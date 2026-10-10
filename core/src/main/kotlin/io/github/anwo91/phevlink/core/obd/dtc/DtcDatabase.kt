package io.github.anwo91.phevlink.core.obd.dtc

import java.util.Locale

/**
 * Diagnostic trouble code (DTC) lookup: titles for ~3500 generic OBD-II codes plus a plain-English
 * explanation (system, severity, likely causes, what to check, drive advice) for any valid code.
 *
 * Titles come from codes.tsv (generated from AndrOBD and python-OBD, see THIRD_PARTY_NOTICES.md),
 * loaded lazily from the classpath. Explanations come from, in order:
 *  1. hand-written entries for codes an Outlander PHEV owner is likely to see ([curatedCodes]),
 *  2. rules based on the code number and the wording of the title,
 *  3. a generic "manufacturer-specific / unknown code" explanation.
 *
 * Thread-safe. Never throws for a syntactically valid code, and never crashes if the resource is missing.
 */
object DtcDatabase {

    private const val RESOURCE = "/io/github/anwo91/phevlink/core/obd/dtc/codes.tsv"
    private val CODE = Regex("^[PCBU][0-9A-F]{4}$")
    private val WHITESPACE = Regex("\\s+")

    private class Data(val titles: Map<String, String>, val error: String?) {
        /** All codes, sorted. */
        val sorted: List<String> = titles.keys.sorted()

        /** Search order for title matches: curated codes first, then the rest, each sorted. */
        val searchOrder: List<String> = sorted.filter { it in CuratedDtcs.codes } + sorted.filter { it !in CuratedDtcs.codes }

        /** Lower-case text searched for each code: title plus curated subsystem. */
        val haystack: Map<String, String> = titles.mapValues { (code, title) ->
            val sub = CuratedDtcs[code]?.subsystem
            (if (sub == null) title else "$title $sub").lowercase(Locale.ROOT)
        }
    }

    private val data: Data by lazy { load() }

    /** Number of codes with a known title. */
    val size: Int get() = data.titles.size

    /** A problem found while loading codes.tsv (missing, unreadable or malformed lines), or null. For a diagnostics screen. */
    val loadError: String? get() = data.error

    /** Codes that have a hand-written (curated) explanation, sorted. */
    val curatedCodes: Set<String> by lazy { java.util.Collections.unmodifiableSet(CuratedDtcs.codes.toSortedSet()) }

    /** Upper-cases and trims a code: " p0a80 " -> "P0A80". Does not validate. */
    fun normalize(code: String): String = code.trim().uppercase(Locale.ROOT)

    /** True for P/C/B/U followed by exactly 4 hex digits (after [normalize]). */
    fun isValid(code: String): Boolean = CODE.matches(normalize(code))

    /** Title only, null when unknown. Accepts "p0a80", " P0A80 ". */
    fun title(code: String): String? = data.titles[normalize(code)]

    /**
     * Full explanation for any syntactically valid code (P/C/B/U + 4 hex digits).
     * @throws IllegalArgumentException for invalid syntax.
     */
    fun explain(code: String): DtcExplanation {
        val c = normalize(code)
        require(CODE.matches(c)) {
            "Not a valid trouble code: '$code' (expected P, C, B or U followed by 4 hex digits, e.g. P0A80)"
        }
        val dbTitle = data.titles[c]
        val curated = CuratedDtcs[c]
        if (curated != null) {
            return DtcExplanation(
                code = c,
                title = dbTitle ?: curated.subsystem,
                system = curated.system,
                subsystem = curated.subsystem,
                severity = curated.severity,
                summary = curated.summary,
                causes = curated.causes,
                checks = curated.checks,
                driveAdvice = curated.driveAdvice,
                manufacturerSpecific = DtcRules.isManufacturerRange(c),
                curated = true,
                known = true,
            )
        }
        return DtcRules.explain(c, dbTitle)
    }

    /**
     * Search titles and codes, for a lookup screen. Case-insensitive, at most [limit] results.
     * Order: exact code match, then codes starting with the query, then title matches (all words
     * must appear; codes with a curated explanation first). Pairs are (code, title).
     */
    fun search(query: String, limit: Int = 50): List<Pair<String, String>> {
        if (limit <= 0) return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val d = data
        val upper = q.uppercase(Locale.ROOT)
        val out = LinkedHashMap<String, String>()

        if (CODE.matches(upper)) out[upper] = d.titles[upper] ?: explain(upper).title

        if (upper.length <= 5 && upper.all { it.isLetterOrDigit() }) {
            for (code in d.sorted) {
                if (out.size >= limit) break
                if (code.startsWith(upper)) out.putIfAbsent(code, d.titles.getValue(code))
            }
        }

        if (out.size < limit) {
            val words = q.lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }
            for (code in d.searchOrder) {
                if (out.size >= limit) break
                if (code in out) continue
                val text = d.haystack.getValue(code)
                if (words.all { text.contains(it) }) out[code] = d.titles.getValue(code)
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
