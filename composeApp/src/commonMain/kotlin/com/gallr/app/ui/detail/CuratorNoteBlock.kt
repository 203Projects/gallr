package com.gallr.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ExhibitionImage
import com.gallr.shared.data.network.nativeSupabaseImageUrl

/** Fixed wording of the detail screen's note block (spec 090 FR-013). */
internal data class CuratorNoteCopy(
    val label: String,
    val tipLabel: String,
)

internal fun curatorNoteCopy(language: AppLanguage): CuratorNoteCopy =
    when (language) {
        AppLanguage.KO -> CuratorNoteCopy(label = "큐레이터의 시선", tipLabel = "관람 팁")
        AppLanguage.EN -> CuratorNoteCopy(label = "CURATOR'S EYE", tipLabel = "Viewing tip")
    }

/**
 * The full note on the exhibition page: the cover as a thumbnail beside the label and hook, the insight paragraphs,
 * the viewing tip and a row of fact chips (DESIGN.md "Curator's note block"). Rendered only when a note exists; the
 * caller shows nothing otherwise. [factChips] come from `curatorNoteFactChips`; an empty list draws no row.
 * [images] are the exhibition's gallery pictures in staff order: the insight in the same place shows its picture
 * above its text, with the credit under it.
 */
@Composable
internal fun CuratorNoteBlock(
    note: CuratorNote,
    lang: AppLanguage,
    coverImageUrl: String?,
    factChips: List<String>,
    modifier: Modifier = Modifier,
    images: List<ExhibitionImage> = emptyList(),
) {
    val copy = curatorNoteCopy(lang)
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
        Spacer(Modifier.height(GallrSpacing.md))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (coverImageUrl != null) {
                Box(
                    modifier =
                        Modifier
                            .size(NOTE_THUMBNAIL_SIZE)
                            .background(scheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = nativeSupabaseImageUrl(coverImageUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Spacer(Modifier.width(GallrSpacing.md))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = copy.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(GallrSpacing.sm))
                Text(
                    text = note.hook(lang),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onBackground,
                )
            }
        }
        note.insights(lang).forEachIndexed { index, paragraph ->
            Spacer(Modifier.height(GallrSpacing.md))
            images.getOrNull(index)?.let { image ->
                NoteImage(image = image, lang = lang)
                Spacer(Modifier.height(GallrSpacing.sm))
            }
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onBackground,
            )
        }
        val tip = note.tip(lang)
        if (tip.isNotBlank()) {
            Spacer(Modifier.height(GallrSpacing.md))
            Text(
                text = "${copy.tipLabel} · $tip",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = scheme.onBackground,
            )
        }
        if (factChips.isNotEmpty()) {
            Spacer(Modifier.height(GallrSpacing.md))
            Row(
                horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                factChips.forEach { chip ->
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onBackground,
                        maxLines = 1,
                        modifier =
                            Modifier
                                .border(1.dp, scheme.outlineVariant)
                                .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs),
                    )
                }
            }
        }
        Spacer(Modifier.height(GallrSpacing.md))
    }
}

/** An insight's picture in a frame that follows its shape, with the source's credit under it. */
@Composable
private fun NoteImage(
    image: ExhibitionImage,
    lang: AppLanguage,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(image.frameAspectRatio())
                .background(scheme.surfaceVariant),
    ) {
        AsyncImage(
            model = nativeSupabaseImageUrl(image.url),
            contentDescription = image.alt(lang).ifBlank { null },
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
    if (image.credit.isNotBlank()) {
        Spacer(Modifier.height(GallrSpacing.xs))
        Text(
            text = image.credit,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** The cover beside the heading, square and 0dp like every other detail-screen shape. */
private val NOTE_THUMBNAIL_SIZE = 88.dp
