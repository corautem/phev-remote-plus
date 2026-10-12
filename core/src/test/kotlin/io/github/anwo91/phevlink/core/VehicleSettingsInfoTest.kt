package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.model.VehicleSettingsCatalog
import io.github.anwo91.phevlink.core.model.VehicleSettingsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every setting the app can show has an explanation in every language, and the theft-alarm group has names. */
class VehicleSettingsInfoTest {
    private val langs = listOf("en", "nb", "da", "de", "sv")

    @Test
    fun everyNamedSettingIsExplainedInEveryLanguage() {
        for (lang in langs) for (def in VehicleSettingsCatalog.all) {
            val info = VehicleSettingsText.info(def.id, lang)
            val whenText = VehicleSettingsText.whenToChange(def.id, lang)
            assertNotNull("info.${def.id} in $lang", info)
            assertNotNull("when.${def.id} in $lang", whenText)
            assertTrue("info.${def.id} in $lang is blank", info!!.isNotBlank())
        }
    }

    @Test
    fun unknownOfferedSettingsAreExplained() {
        for (lang in langs) for (id in listOf(30, 38, 39)) assertNotNull("info.$id in $lang", VehicleSettingsText.info(id, lang))
    }

    @Test
    fun theftAlarmGroupHasResearchedNames() {
        assertEquals("Theft alarm", VehicleSettingsText.researchedName(23, "en"))
        assertEquals("Diebstahlwarnanlage", VehicleSettingsText.researchedName(23, "de"))
        assertEquals("Level 4 (least sensitive)", VehicleSettingsText.researchedOption(41, 3, "en"))
        for (lang in langs) for (id in VehicleSettingsCatalog.HIDDEN_IDS) assertNotNull("rname.$id in $lang", VehicleSettingsText.researchedName(id, lang))
    }
}
