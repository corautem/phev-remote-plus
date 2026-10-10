package io.github.anwo91.phevlink.core.knowledge

import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.json.list
import io.github.anwo91.phevlink.core.json.str
import java.util.concurrent.ConcurrentHashMap

/** A source link for a tip. */
data class TipSource(val title: String, val url: String)

/** A known complaint or problem with the 2014–2017 Outlander PHEV, with explanation and what to do. */
data class Tip(
    val id: String,
    val title: String,
    val category: String,
    val symptoms: List<String>,
    val why: String,
    val whatToDo: List<String>,
    val diy: String,
    val appHelp: String,
    val relatedTests: List<String>,
    val relatedCodes: List<String>,
    val relatedSettings: List<Int>,
    val confidence: String,
    val sources: List<TipSource>,
)

/** A warning sound/chime and whether (and how) it can be changed. [adjust]: app, car-menu, switch, dealer or no. */
data class Beep(
    val id: String,
    val sound: String,
    val whenText: String,
    val adjust: String,
    val how: String,
    val settingIds: List<Int>,
    val notes: String,
    val confidence: String,
    val sources: List<TipSource>,
)

data class TipBook(val tips: List<Tip>, val beeps: List<Beep>) {
    val categories: List<String> get() = tips.map { it.category }.distinct()
    fun tip(id: String): Tip? = tips.firstOrNull { it.id == id }
}

/**
 * The tips knowledge base (`tips_<lang>.json` resources next to this class). Researched content with sources;
 * English is the source, other languages are translations with the same ids. Missing language → English.
 */
object Tips {
    val LANGUAGES = listOf("en", "nb", "sv", "da", "de")
    private const val DIR = "/io/github/anwo91/phevlink/core/knowledge/"
    private val cache = ConcurrentHashMap<String, TipBook>()

    fun load(lang: String = "en"): TipBook {
        val l = if (lang in LANGUAGES) lang else "en"
        return cache.getOrPut(l) { read(l) ?: if (l != "en") load("en") else TipBook(emptyList(), emptyList()) }
    }

    private fun read(lang: String): TipBook? {
        val text = Tips::class.java.getResourceAsStream("${DIR}tips_$lang.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        return try {
            parse(text)
        } catch (e: Exception) {
            null
        }
    }

    fun parse(text: String): TipBook {
        val root = Json.parseObject(text)
        val tips = root.list("tips").orEmpty().mapNotNull { item ->
            @Suppress("UNCHECKED_CAST") val m = item as? Map<String, Any?> ?: return@mapNotNull null
            Tip(
                id = m.str("id") ?: return@mapNotNull null,
                title = m.str("title").orEmpty(),
                category = m.str("category").orEmpty(),
                symptoms = m.strings("symptoms"),
                why = m.str("why").orEmpty(),
                whatToDo = m.strings("whatToDo"),
                diy = m.str("diy").orEmpty(),
                appHelp = m.str("appHelp").orEmpty(),
                relatedTests = m.strings("relatedTests"),
                relatedCodes = m.strings("relatedCodes"),
                relatedSettings = m.ints("relatedSettings"),
                confidence = m.str("confidence").orEmpty(),
                sources = m.sources(),
            )
        }
        val beeps = root.list("beeps").orEmpty().mapNotNull { item ->
            @Suppress("UNCHECKED_CAST") val m = item as? Map<String, Any?> ?: return@mapNotNull null
            Beep(
                id = m.str("id") ?: return@mapNotNull null,
                sound = m.str("sound").orEmpty(),
                whenText = m.str("when").orEmpty(),
                adjust = m.str("adjust").orEmpty(),
                how = m.str("how").orEmpty(),
                settingIds = m.ints("settingIds"),
                notes = m.str("notes").orEmpty(),
                confidence = m.str("confidence").orEmpty(),
                sources = m.sources(),
            )
        }
        return TipBook(tips, beeps)
    }

    private fun Map<String, Any?>.strings(key: String): List<String> = list(key).orEmpty().mapNotNull { it as? String }
    private fun Map<String, Any?>.ints(key: String): List<Int> = list(key).orEmpty().mapNotNull { (it as? Number)?.toInt() }

    private fun Map<String, Any?>.sources(): List<TipSource> = list("sources").orEmpty().mapNotNull { s ->
        @Suppress("UNCHECKED_CAST") val sm = s as? Map<String, Any?> ?: return@mapNotNull null
        val url = sm.str("url") ?: return@mapNotNull null
        TipSource(sm.str("title") ?: url, url)
    }
}
