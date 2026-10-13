package com.gallr.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.map.MissingImageResolver
import org.maplibre.compose.map.ResolvedStyleImage
import kotlin.math.roundToInt

/** Ring colour of a pin: the accent marks a saved exhibition (DESIGN.md, active indicator), black otherwise. */
internal fun mapPinRingColor(saved: Boolean): Color = if (saved) GallrAccent.activeIndicator else Color.Black

/**
 * Draws pin images when the map asks for them. The map requests an id only once a visible pin needs it, so
 * covers load as pins come into view; a cover that cannot be loaded leaves the plain logo pin.
 */
@Composable
internal fun rememberMapPinImageResolver(): MissingImageResolver {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val context = LocalPlatformContext.current
    return remember(density, layoutDirection, context) {
        val coverPx = with(density) { MAP_PIN_COVER_WIDTH.toPx() }.roundToInt()
        MissingImageResolver { request ->
            val pin = parseMapPinImageId(request.id) ?: return@MissingImageResolver null
            val cover = pin.coverUrl?.let { loadCover(context, it, coverPx) }
            ResolvedStyleImage.fromPainter(
                painter = LogoPinPainter(cover = cover, ring = mapPinRingColor(pin.saved)),
                density = density,
                layoutDirection = layoutDirection,
                size = MAP_PIN_IMAGE_SIZE,
            )
        }
    }
}

private val log = AppLog.tagged("MapPinImageResolver")

private suspend fun loadCover(
    context: PlatformContext,
    coverUrl: String,
    sizePx: Int,
): Painter? =
    try {
        val request =
            ImageRequest
                .Builder(context)
                .data(nativeSupabaseImageUrl(coverUrl))
                .size(sizePx)
                .build()
        val result = SingletonImageLoader.get(context).execute(request)
        if (result !is SuccessResult) log.warn("map_pin_cover_unavailable")
        (result as? SuccessResult)?.image?.asPainter(context)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.warn("map_pin_cover_load_failed", error)
        null
    }
