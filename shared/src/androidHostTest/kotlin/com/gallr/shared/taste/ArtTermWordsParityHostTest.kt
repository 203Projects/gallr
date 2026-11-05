package com.gallr.shared.taste

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 101 FR-019a parity contract: Admin suggests terms with the same words the app uses to detect them, so the
 * checked-in word file must equal [ART_TERM_VOCABULARY]. Admin's suggestion test reads the same file.
 */
class ArtTermWordsParityHostTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun writesAFreshCopyUnderBuildForRegeneration() {
        val out = File("build/parity/art-term-words.json")
        out.parentFile.mkdirs()
        out.writeText(json.encodeToString(JsonObject.serializer(), wordFile()) + "\n")
        assertTrue(out.length() > 0)
    }

    @Test
    fun checkedInFileMatchesTheVocabulary() {
        val file = File(CHECKED_IN_PATH)
        assertTrue(file.exists(), "missing ${file.path}; copy build/parity/art-term-words.json there")
        assertEquals(wordFile(), Json.parseToJsonElement(file.readText()), "art term words drifted from the app")
    }

    private fun wordFile(): JsonObject =
        buildJsonObject {
            putJsonArray("terms") {
                ART_TERM_VOCABULARY.forEach { pattern ->
                    addJsonObject {
                        put("id", pattern.term.id)
                        putJsonArray("korean") { pattern.korean.forEach { add(it) } }
                        putJsonArray("english") { pattern.english.forEach { add(it) } }
                    }
                }
            }
        }

    private companion object {
        const val CHECKED_IN_PATH = "../specs/101-taste-listening/contracts/art-term-words.json"
    }
}
