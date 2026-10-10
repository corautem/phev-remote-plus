package io.github.anwo91.phevlink.core.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Translations of the vehicle settings (names, option labels, group titles). English comes from
 * [VehicleSettingsCatalog]; other languages from `vehicle_settings_<lang>.tsv` next to this class
 * (`name.<id>`, `opt.<id>.<option>`, `group.<English group title>`), based on the official app's translations.
 * Anything missing falls back to English.
 */
object VehicleSettingsText {
    private const val DIR = "/io/github/anwo91/phevlink/core/model/"
    private val tables = ConcurrentHashMap<String, Map<String, String>>()

    private fun table(lang: String): Map<String, String> = if (lang == "en") emptyMap() else tables.getOrPut(lang) { load(lang) }

    fun name(id: Int, lang: String): String = table(lang)["name.$id"] ?: VehicleSettingsCatalog.def(id)?.name ?: "#$id"

    fun group(title: String, lang: String): String = table(lang)["group.$title"] ?: title

    /** Option label; when labels collide among [shown] options, a " (n)" suffix keeps them apart. */
    fun option(id: Int, option: Int, lang: String, shown: List<Int>? = null): String {
        if (lang == "en") return VehicleSettingsCatalog.optionLabel(id, option)
        val base = table(lang)["opt.$id.$option"] ?: return VehicleSettingsCatalog.optionLabel(id, option)
        val others = shown ?: return base
        val clash = others.filter { it != option }.any { (table(lang)["opt.$id.$it"] ?: "") == base }
        return if (clash) "$base (${option + 1})" else base
    }

    /** [VehicleSettingsCatalog.visibleGroups] with every text in [lang]. */
    fun visibleGroups(settings: Map<Int, CarSetting>, lang: String): List<VisibleGroup> {
        val groups = VehicleSettingsCatalog.visibleGroups(settings)
        if (lang == "en") return groups
        return groups.map { g ->
            VisibleGroup(
                group(g.title, lang),
                g.items.map { item ->
                    val shown = item.options.map { it.first }
                    VisibleSetting(
                        item.def.copy(name = name(item.id, lang)),
                        item.value,
                        option(item.id, item.value, lang, shown),
                        item.options.map { (o, _) -> o to option(item.id, o, lang, shown) },
                    )
                },
            )
        }
    }

    private fun load(lang: String): Map<String, String> {
        val stream = VehicleSettingsText::class.java.getResourceAsStream("${DIR}vehicle_settings_$lang.tsv") ?: return emptyMap()
        return try {
            val map = HashMap<String, String>()
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val v = line.substring(tab + 1).trim()
                    if (v.isNotEmpty()) map[line.substring(0, tab).trim()] = v
                }
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
