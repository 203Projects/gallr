package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.observability.AppLog
import gallr.composeapp.generated.resources.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.style.BaseStyle

/**
 * The quiet map style with its place and street labels in the reader's language. OpenFreeMap's stock labels pair
 * the Latin and Hangul names ("Achasan-ro 아차산로"), which doubles every label in Seoul. Korean shows the Korean
 * name and English the English one, each falling back to the name on the ground. Only labels drawn from a name
 * change; every other property of the style is kept.
 */
internal fun localizedMapStyle(
    styleJson: String,
    language: AppLanguage,
): String {
    val style = Json.parseToJsonElement(styleJson).jsonObject
    val layers = style["layers"] as? JsonArray ?: return styleJson
    val label = nameLabel(language)
    val localized = JsonArray(layers.map { layer -> layer.withNameLabel(label) })
    return JsonObject(style + ("layers" to localized)).toString()
}

/**
 * The bundled quiet style labelled for [language]. A blank style stands in for the moment the file is read; if it
 * cannot be read, the stock OpenFreeMap style takes over with its paired labels.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun rememberQuietSeoulMapStyle(language: AppLanguage): BaseStyle {
    val style by produceState<BaseStyle>(initialValue = LOADING_STYLE, language) {
        value =
            runCatching { Res.readBytes(QUIET_SEOUL_MAP_STYLE_RESOURCE).decodeToString() }
                .map { json -> BaseStyle.Json(localizedMapStyle(json, language)) }
                .getOrElse { error ->
                    mapStyleLog.warn("map_style_read_failed", error)
                    BaseStyle.Uri(FALLBACK_SEOUL_MAP_STYLE)
                }
    }
    return style
}

private fun JsonElement.withNameLabel(label: JsonArray): JsonElement {
    val layer = this as? JsonObject ?: return this
    val layout = layer["layout"] as? JsonObject ?: return this
    val textField = layout["text-field"] ?: return this
    if (!textField.toString().contains(NAME_PROPERTY_MARKER)) return this
    return JsonObject(layer + ("layout" to JsonObject(layout + ("text-field" to label))))
}

private fun nameLabel(language: AppLanguage): JsonArray =
    when (language) {
        AppLanguage.KO -> coalesceNames("name:ko", "name:nonlatin", "name")
        AppLanguage.EN -> coalesceNames("name:en", "name_en", "name:latin", "name")
    }

private fun coalesceNames(vararg properties: String): JsonArray =
    JsonArray(
        listOf(JsonPrimitive("coalesce")) +
            properties.map { property -> JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive(property))) },
    )

/** Matches `"name"`, `"name_en"` and `"name:…"` property reads, not `"ref"` shields. */
private const val NAME_PROPERTY_MARKER = "\"name"

private val LOADING_STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")

private val mapStyleLog = AppLog.tagged("MapStyle")
