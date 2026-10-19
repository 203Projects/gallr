package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.ui.theme.HomeVisualTokens
import com.gallr.app.ui.theme.bottomScrim
import com.gallr.app.ui.theme.bottomScrimBand
import com.gallr.app.ui.theme.flatScrim
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import com.gallr.shared.home.HomeCollection

/**
 * 큐레이션 (DESIGN.md, Home tab): the timing collections and what visitors opened most, two to a row. Each tile
 * shows a cover borrowed from its exhibitions under the scrim, with the headline and what gathers them in white.
 */
@Composable
internal fun CurationGrid(
    collections: List<HomeCollection>,
    lang: AppLanguage,
    onTap: (HomeCollection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = GallrSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
    ) {
        collections.chunked(GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm)) {
                row.forEach { collection ->
                    CurationTile(
                        collection = collection,
                        lang = lang,
                        onTap = { onTap(collection) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size < GRID_COLUMNS) Spacer(Modifier.weight((GRID_COLUMNS - row.size).toFloat()))
            }
        }
    }
}

@Composable
private fun CurationTile(
    collection: HomeCollection,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = collection.localizedTitle(lang)
    val subtitle = collectionSubtitle(collection, lang)
    var tileHeightPx by remember { mutableStateOf(0) }
    var captionHeightPx by remember { mutableStateOf(0) }
    CollectionTileFrame(
        collection = collection,
        description = "$title, $subtitle",
        onTap = onTap,
        modifier = modifier.aspectRatio(CURATION_ASPECT_RATIO).onSizeChanged { tileHeightPx = it.height },
        scrim = {
            Modifier.bottomScrim(bottomScrimBand(tileHeightPx, captionHeightPx), HomeVisualTokens.SCRIM_ALPHA)
        },
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .onSizeChanged { captionHeightPx = it.height }
                    .padding(GallrSpacing.sm + GallrSpacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 상황별·주제별 (DESIGN.md, Home tab): situations, neighbourhoods and art terms as a rail of smaller tiles, the
 * headline at the top and the hashtag line at the bottom over one even scrim.
 */
@Composable
internal fun ThemeRail(
    collections: List<HomeCollection>,
    lang: AppLanguage,
    onTap: (HomeCollection) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Larger type gets a wider tile so a two-line headline still fits above the hashtag line.
    val tileWidth = THEME_TILE_WIDTH * LocalDensity.current.fontScale.coerceIn(1f, THEME_TILE_MAX_SCALE)
    LazyRow(
        contentPadding = PaddingValues(horizontal = GallrSpacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(collections, key = { it.key }) { collection ->
            val title = collection.localizedTitle(lang)
            val subtitle = collectionSubtitle(collection, lang)
            CollectionTileFrame(
                collection = collection,
                description = "$title, $subtitle",
                onTap = { onTap(collection) },
                modifier = Modifier.width(tileWidth).aspectRatio(THEME_ASPECT_RATIO),
                scrim = { Modifier.flatScrim(HomeVisualTokens.SCRIM_ALPHA) },
            ) {
                Text(
                    text = balancedHeadline(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(GallrSpacing.sm + GallrSpacing.xs),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(GallrSpacing.sm + GallrSpacing.xs),
                )
            }
        }
    }
}

/** A collection tile's shell: the rounded frame, the borrowed cover in colour, the scrim, then the caption. */
@Composable
private fun CollectionTileFrame(
    collection: HomeCollection,
    description: String,
    onTap: () -> Unit,
    modifier: Modifier,
    scrim: () -> Modifier,
    caption: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var imageFailed by remember(collection.coverImageUrl) { mutableStateOf(false) }
    val coverUrl = collection.coverImageUrl?.takeUnless { imageFailed }
    Box(
        modifier =
            modifier
                .clip(HomeVisualTokens.shape)
                .border(1.dp, scheme.outlineVariant, HomeVisualTokens.shape)
                .background(scheme.surfaceVariant)
                .clickable(role = Role.Button, onClick = onTap)
                .semantics { contentDescription = description },
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = nativeSupabaseImageUrl(coverUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(Modifier.matchParentSize().then(scrim()))
        caption()
    }
}

private const val GRID_COLUMNS = 2
private const val CURATION_ASPECT_RATIO = 0.9f
private val THEME_TILE_WIDTH = 156.dp

/** At 1.4× two tiles still show on a 360dp-wide phone. */
private const val THEME_TILE_MAX_SCALE = 1.4f
private const val THEME_ASPECT_RATIO = 156f / 200f
