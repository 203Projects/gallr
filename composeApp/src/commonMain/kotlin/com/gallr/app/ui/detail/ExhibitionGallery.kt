package com.gallr.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.ui.theme.HomeVisualTokens
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ExhibitionImage
import com.gallr.shared.data.model.galleryCounter
import com.gallr.shared.data.network.nativeSupabaseImageUrl

/**
 * The top of the exhibition page (DESIGN.md "Exhibition gallery"): the cover alone, or the cover and the gallery
 * images as swipeable pages in the same 16:9 frame, with a counter over the picture and the current picture's
 * credit under it. [pages] come from `Exhibition.galleryPages`, cover first.
 */
@Composable
internal fun ExhibitionGallery(
    pages: List<ExhibitionImage>,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    if (pages.size == 1) {
        GalleryPicture(page = pages.first(), lang = lang, modifier = modifier)
        return
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val current = pagerState.currentPage.coerceIn(0, pages.lastIndex)
    Column(modifier = modifier.fillMaxWidth()) {
        Box {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                GalleryPicture(page = pages[page], lang = lang)
            }
            Text(
                text = galleryCounter(current, pages.size),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(GallrSpacing.sm)
                        .clip(GallrShapeTokens.small)
                        .background(Color.Black.copy(alpha = HomeVisualTokens.SCRIM_ALPHA), GallrShapeTokens.small)
                        .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs)
                        .clearAndSetSemantics { contentDescription = galleryPosition(current, pages.size, lang) },
            )
        }
        val credit = pages[current].credit
        if (credit.isNotBlank()) {
            Spacer(Modifier.height(GallrSpacing.xs))
            Text(
                text = credit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin),
            )
        }
    }
}

@Composable
private fun GalleryPicture(
    page: ExhibitionImage,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    val alt = page.alt(lang)
    AsyncImage(
        model = nativeSupabaseImageUrl(page.url),
        contentDescription = alt.ifBlank { null },
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(GALLERY_ASPECT_RATIO)
                .semantics {},
    )
}

/** What a screen reader says for the counter. */
internal fun galleryPosition(
    index: Int,
    count: Int,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> "이미지 ${count}장 중 ${index + 1}번째"
        AppLanguage.EN -> "Picture ${index + 1} of $count"
    }

private const val GALLERY_ASPECT_RATIO = 16f / 9f
