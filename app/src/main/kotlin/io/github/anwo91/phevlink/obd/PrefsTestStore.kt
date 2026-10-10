package io.github.anwo91.phevlink.obd

import android.content.Context
import io.github.anwo91.phevlink.core.obd.livetest.TestStore

/** Live-test baselines and history (core [TestStore]) in the "livetests" SharedPreferences. */
class PrefsTestStore(context: Context) : TestStore {
    private val prefs = context.applicationContext.getSharedPreferences("livetests", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
