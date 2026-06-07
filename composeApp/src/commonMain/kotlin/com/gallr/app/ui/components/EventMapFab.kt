package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.shared.data.model.Event
import com.gallr.shared.util.parseHexColor

/**
 * Persistent floating button shown on the Map tab when an event is active.
 * A circular FAB displaying the event's [Event.coverImageUrl] cropped to a
 * circle, with a 2dp brand-color ring. When the cover image is absent (or
 * fails to load), a solid brand-color circle shows through — no text. Tap
 * invokes [onTap], which the caller wires to the Event Detail route.
 */
@Composable
fun EventMapFab(
    event: Event,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black

    Box(
        modifier = modifier
            .shadow(elevation = 4.dp, shape = CircleShape)
            .size(60.dp)
            .clip(CircleShape)
            .background(brand)
            .border(2.dp, brand, CircleShape)
            .clickable(onClick = onTap),
    ) {
        // Hero image cropped to the circle; absent / failed → brand color shows through.
        if (event.coverImageUrl != null) {
            AsyncImage(
                model = event.coverImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape),
            )
        }
    }
}
