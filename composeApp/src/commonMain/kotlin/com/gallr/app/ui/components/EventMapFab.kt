package com.gallr.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.util.parseHexColor
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * Persistent floating button on the Map tab. Circular cover-image FAB with a
 * brand-color ring. When [event] changes (multi-event cycling), the cover image
 * and ring color cross-fade. Tap navigates to the currently-shown event.
 */
@Composable
fun EventMapFab(
    event: Event,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = Clock.System.todayIn(TimeZone.of("Asia/Seoul"))
    val desc = "${event.localizedName(lang)} · ${event.statusEyebrow(today, lang)}"

    Crossfade(
        targetState = event,
        animationSpec = tween(durationMillis = 260),
        modifier =
            modifier
                .size(60.dp)
                .semantics {
                    contentDescription = desc
                    liveRegion = LiveRegionMode.Polite
                }.clickable(onClick = onTap),
    ) { current ->
        val brand = parseHexColor(current.brandColor)?.let { Color(it) } ?: Color.Black
        Box(
            modifier =
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(brand)
                    .border(2.dp, brand, CircleShape),
        ) {
            if (current.coverImageUrl != null) {
                AsyncImage(
                    model = current.coverImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().clip(CircleShape),
                )
            }
        }
    }
}
