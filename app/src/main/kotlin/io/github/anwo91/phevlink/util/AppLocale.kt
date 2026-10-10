package io.github.anwo91.phevlink.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import io.github.anwo91.phevlink.R
import java.util.Locale

/**
 * The app's own language ("Language" in Settings), independent of the phone's.
 *
 * How to use it from new code:
 *  - Activities: `override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }`.
 *  - Composables: plain `stringResource(R.string.x)`; the activity is already localized.
 *  - Everything else that only has the Application context (services, receivers, widgets,
 *    tiles, notifications, repositories, view models):
 *    `AppLocale.localized(context).getString(R.string.x)`. Resolve it at the moment the
 *    text is built, never cache the result, so a language change applies right away.
 *  - The language for data that is not in Android resources (e.g. fault-code texts):
 *    [effectiveLanguage].
 *
 * Android 13+ keeps the choice itself (LocaleManager; also editable under the system's
 * Settings → Apps → PHEV Link → Language) and applies it to every context of the app.
 * Below 13 the choice lives in the "locale" SharedPreferences and contexts are wrapped.
 */
object AppLocale {
    /** Follow the phone's language. */
    const val SYSTEM = ""

    /** Languages with translations (res/values-xx), in picker order. Keep in step with res/xml/locales_config.xml. */
    val SUPPORTED: List<String> = listOf("en", "nb", "sv", "da", "de")

    private const val PREFS = "locale"
    private const val KEY_TAG = "tag"

    private class Cached(val tag: String, val baseConfig: Configuration, val context: Context)

    @Volatile private var cached: Cached? = null

    /** The chosen language: [SYSTEM] ("") or one of [SUPPORTED]. */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system's per-app language screen can change it too, so ask the system.
            return Api33.get(context)?.let { supportedTag(it) } ?: SYSTEM
        }
        return stored(context)
    }

    /**
     * Saves the choice and applies it. Android 13+ then recreates the activities by itself;
     * below 13 the caller must recreate the current activity (`activity.recreate()`).
     */
    fun set(context: Context, tag: String) {
        val t = if (tag in SUPPORTED) tag else SYSTEM
        prefs(context).edit().putString(KEY_TAG, t).apply()
        cached = null
        if (Build.VERSION.SDK_INT >= 33) {
            Api33.set(context, t)
        } else {
            Locale.setDefault(if (t.isEmpty()) systemLocale() else Locale.forLanguageTag(t))
        }
    }

    /** For `attachBaseContext`: [base] with the chosen language, or [base] itself when following the phone. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base // the system applies the per-app language itself
        val tag = stored(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        // java.time / String.format defaults follow the app language too.
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * A context whose resources are in the app language, for code without an activity
     * (services, notifications, widgets, tiles, repositories). Use it for reading strings
     * and other resources only.
     */
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val base = context.applicationContext ?: context
        val tag = stored(base)
        if (tag.isEmpty()) return context
        val baseConfig = base.resources.configuration
        cached?.let { if (it.tag == tag && it.baseConfig == baseConfig) return it.context }
        val wrapped = wrap(base)
        cached = Cached(tag, Configuration(baseConfig), wrapped)
        return wrapped
    }

    /**
     * The language the UI is shown in, as one of "en", "nb", "sv", "da", "de" — for texts
     * that do not come from Android resources (e.g. the OBD fault-code database).
     * Norwegian Nynorsk and generic Norwegian map to "nb"; anything else unsupported to "en".
     */
    fun effectiveLanguage(context: Context): String {
        val res = localized(context)
        // Each values-xx folder states its own code, so this is exactly what the screens show.
        val shown = res.getString(R.string.app_language_code)
        if (shown != "en") return shown
        val first: Locale? = res.resources.configuration.locales[0]
        return if (first?.language == "nn" || first?.language == "no") "nb" else "en"
    }

    /** Locale for dates and weekday names, matching the language the screens are shown in. */
    fun uiLocale(context: Context): Locale =
        Locale.forLanguageTag(localized(context).getString(R.string.app_language_code))

    /** The language's own name for the picker ("Norsk (bokmål)"), or null for [SYSTEM]. */
    fun displayName(tag: String): String? = when (tag) {
        "en" -> "English"
        "nb" -> "Norsk (bokmål)"
        "sv" -> "Svenska"
        "da" -> "Dansk"
        "de" -> "Deutsch"
        else -> null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stored(context: Context): String =
        prefs(context).getString(KEY_TAG, SYSTEM)?.takeIf { it in SUPPORTED } ?: SYSTEM

    private fun supportedTag(locale: Locale): String? = when (val lang = locale.language) {
        "nb", "no", "nn" -> "nb"
        in SUPPORTED -> lang
        else -> null
    }

    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()

    @RequiresApi(33)
    private object Api33 {
        fun get(context: Context): Locale? {
            val list = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
            return if (list.isEmpty) null else list[0]
        }

        fun set(context: Context, tag: String) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }
}
