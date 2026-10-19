package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.AppLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Map labels in the reader's language instead of OpenFreeMap's paired Latin and Hangul names. */
class MapStyleLanguageTest {
    @Test
    fun koreanLabelsUseTheKoreanNameAndFallBackToTheLocalName() {
        val label = textField(localizedMapStyle(STYLE, AppLanguage.KO), "street_label")

        assertEquals(
            Json.parseToJsonElement("""["coalesce",["get","name:ko"],["get","name:nonlatin"],["get","name"]]"""),
            label,
        )
    }

    @Test
    fun englishLabelsUseTheEnglishNameAndFallBackToTheLatinAndLocalNames() {
        val label = textField(localizedMapStyle(STYLE, AppLanguage.EN), "street_label")

        assertEquals(
            Json.parseToJsonElement(
                """["coalesce",["get","name:en"],["get","name_en"],["get","name:latin"],["get","name"]]""",
            ),
            label,
        )
    }

    @Test
    fun everyNameLabelIsLocalizedAndNoneStillPairsTwoScripts() {
        val localized = localizedMapStyle(STYLE, AppLanguage.KO)

        assertEquals(textField(localized, "street_label"), textField(localized, "place_label"))
        assertFalse("concat" in localized)
    }

    @Test
    fun layersWithoutNameLabelsAndTheRestOfTheStyleAreUntouched() {
        val original = Json.parseToJsonElement(STYLE).jsonObject
        val localized = Json.parseToJsonElement(localizedMapStyle(STYLE, AppLanguage.EN)).jsonObject

        assertEquals(original["sources"], localized["sources"])
        assertEquals(original["glyphs"], localized["glyphs"])
        assertEquals(layer(original, "background"), layer(localized, "background"))
        assertEquals(layer(original, "shield"), layer(localized, "shield"))
        assertNull(layer(localized, "background")["layout"])
    }

    private fun textField(
        styleJson: String,
        id: String,
    ) = layer(Json.parseToJsonElement(styleJson).jsonObject, id)["layout"]!!.jsonObject["text-field"]

    private fun layer(
        style: JsonObject,
        id: String,
    ): JsonObject =
        (style["layers"] as JsonArray)
            .map { it.jsonObject }
            .single { it["id"].toString() == "\"$id\"" }

    private companion object {
        val PAIRED_LABEL =
            """["case",["has","name:nonlatin"],["concat",["get","name:latin"]," ",["get","name:nonlatin"]],""" +
                """["coalesce",["get","name_en"],["get","name"]]]"""

        val STYLE =
            """
            {"version":8,"glyphs":"https://tiles.example/fonts/{fontstack}/{range}.pbf",
             "sources":{"openmaptiles":{"type":"vector","url":"https://tiles.example/planet"}},
             "layers":[
               {"id":"background","type":"background","paint":{"background-color":"rgb(242,243,240)"}},
               {"id":"street_label","type":"symbol","source":"openmaptiles",
                "layout":{"text-field":$PAIRED_LABEL,"text-size":13},"paint":{"text-color":"#666"}},
               {"id":"place_label","type":"symbol","source":"openmaptiles",
                "layout":{"text-field":${PAIRED_LABEL.replace("\" \"", "\"\\n\"")}}},
               {"id":"shield","type":"symbol","source":"openmaptiles","layout":{"text-field":["get","ref"]}}
             ]}
            """.trimIndent()
    }
}
