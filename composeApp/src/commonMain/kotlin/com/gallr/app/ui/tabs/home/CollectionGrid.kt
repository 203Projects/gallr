package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.graphics.GrainWashSpec
import com.gallr.app.ui.graphics.grainWash
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import com.gallr.shared.home.HomeCollection

/**
 * Themed collections (DESIGN.md, Home tab): square cards two to a row. Each shows a concept rather than a venue:
 * a borrowed cover drained to monochrome under the grain wash, with the headline that frames the theme and a
 * hashtag-style line saying what gathers the exhibitions and how many there are.
 */
@Composable
internal fun CollectionGrid(
    collections: List<HomeCollection>,
    lang: AppLanguage,
    onTap: (HomeCollection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = GallrSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
    ) {
        collections.chunked(COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm)) {
                row.forEach { collection ->
                    CollectionCard(
                        collection = collection,
                        lang = lang,
                        onTap = { onTap(collection) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size < COLUMNS) Spacer(Modifier.weight((COLUMNS - row.size).toFloat()))
            }
        }
    }
}

@Composable
private fun CollectionCard(
    collection: HomeCollection,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = collection.localizedTitle(lang)
    val subtitle = collectionSubtitle(collection, lang)
    val scheme = MaterialTheme.colorScheme
    var imageFailed by remember(collection.coverImageUrl) { mutableStateOf(false) }
    val coverUrl = collection.coverImageUrl?.takeUnless { imageFailed }

    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .border(1.dp, scheme.outline, RectangleShape)
                .background(scheme.onBackground)
                .clickable(role = Role.Button, onClick = onTap)
                .semantics { contentDescription = "$title, $subtitle" },
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = nativeSupabaseImageUrl(coverUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(MONOCHROME),
                onError = { imageFailed = true },
                modifier = Modifier.matchParentSize().grainWash(COLLECTION_WASH),
            )
        }
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(GallrSpacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (coverUrl != null) Color.White else scheme.background,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = (if (coverUrl != null) Color.White else scheme.background).copy(alpha = SUBTITLE_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val COLUMNS = 2
private const val SUBTITLE_ALPHA = 0.75f
private val MONOCHROME = ColorMatrix().apply { setToSaturation(0f) }

/** Ink: the concept image fades into black from the top down so the headline reads in both themes. */
private val COLLECTION_WASH = GrainWashSpec(color = Color.Black, start = 0f, strength = 0.8f, grain = 0.3f)
