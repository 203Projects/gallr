package com.gallr.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Square gallery identity tile. The text monogram is always drawn underneath, so it stays
 * visible while the curated image loads, when the gallery has none, or when loading fails.
 * Curated images are pre-normalized to squares, so a plain crop never cuts a logo.
 */
@Composable
internal fun GalleryAvatar(
    name: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(3).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
