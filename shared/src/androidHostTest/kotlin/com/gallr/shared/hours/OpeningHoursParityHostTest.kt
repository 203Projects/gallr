package com.gallr.shared.hours

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 089 parity contract: the checked-in parity file must equal what the Kotlin parser and the
 * hand-checked catalogue table produce, so the web port's test can trust it (E-D10).
 */
class OpeningHoursParityHostTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun writesAFreshCopyUnderBuildForRegeneration() {
        val out = File("build/parity/opening-hours-parity.json")
        out.parentFile.mkdirs()
        out.writeText(json.encodeToString(JsonObject.serializer(), OpeningHoursParityFile.build()) + "\n")
        assertTrue(out.length() > 0)
    }

    @Test
    fun checkedInFileMatchesTheParserAndTheCatalogueTable() {
        val file = OpeningHoursParityFile.checkedIn()
        assertTrue(file.exists(), "missing ${file.path}; copy build/parity/opening-hours-parity.json there")
        val checkedIn = Json.parseToJsonElement(file.readText()).jsonObject
        val expected = OpeningHoursParityFile.build()
        assertEquals(expected["statusLabels"], checkedIn["statusLabels"], "status labels drifted")
        val expectedHours = expected.getValue("hours").jsonArray.associateBy { it.jsonObject.id() }
        val actualHours = checkedIn.getValue("hours").jsonArray.associateBy { it.jsonObject.id() }
        assertEquals(expectedHours.keys, actualHours.keys, "parity entries differ")
        expectedHours.forEach { (id, entry) -> assertEquals(entry, actualHours[id], "entry $id drifted") }
    }

    private fun JsonObject.id(): String = getValue("id").jsonPrimitive.content
}
