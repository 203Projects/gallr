package com.gallr.app.ui.route.composer

import com.gallr.shared.data.model.AppLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 089 re-review Section 2: the composer's status-label table must equal `statusLabels` in the shared parity
 * file, which the web page's table is checked against too, so app and page never word a status differently.
 */
class StatusLabelParityHostTest {
    @Test
    fun composerStatusLabelsMatchTheParityFile() {
        val file = File(PARITY_FILE_PATH)
        assertTrue(file.exists(), "missing ${file.path}")
        val labels =
            Json
                .parseToJsonElement(file.readText())
                .jsonObject
                .getValue("statusLabels")
                .jsonObject

        assertEquals(labels.keys, RouteStatusKind.entries.map { it.name }.toSet(), "status kinds differ")
        RouteStatusKind.entries.forEach { kind ->
            val expected = labels.getValue(kind.name).jsonObject
            assertEquals(expected.getValue("ko").jsonPrimitive.content, routeStatusTemplate(kind, AppLanguage.KO))
            assertEquals(expected.getValue("en").jsonPrimitive.content, routeStatusTemplate(kind, AppLanguage.EN))
        }
    }

    private companion object {
        const val PARITY_FILE_PATH = "../specs/089-personal-routes/contracts/opening-hours-parity.json"
    }
}
