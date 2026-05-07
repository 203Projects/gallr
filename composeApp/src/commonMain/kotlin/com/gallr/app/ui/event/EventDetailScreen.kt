package com.gallr.app.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.EventTreatment
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.viewmodel.EventDetailViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.util.parseHexColor

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EventDetailScreen(
    viewModel: EventDetailViewModel,
    lang: AppLanguage,
    bookmarkedIds: Set<String>,
    onToggleBookmark: (String) -> Unit,
    onBack: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val event by viewModel.event.collectAsState()
    val exhibitions by viewModel.exhibitions.collectAsState()
    val venuesKo by viewModel.venuesKo.collectAsState()
    val venuesEn by viewModel.venuesEn.collectAsState()

    val brand = event?.brandColor?.let { parseHexColor(it) }?.let { Color(it) } ?: Color.Black
    val venues = if (lang == AppLanguage.KO) venuesKo else venuesEn

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // ── Top bar ──────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clickable(onClick = onBack),
        ) {
            Text(text = "←", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                text = if (lang == AppLanguage.KO) "아트페어" else "ART EVENT",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        HorizontalDivider(color = Color.Black, thickness = 1.dp)

        val current = event
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (lang == AppLanguage.KO) "불러오는 중…" else "Loading…",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ── Branded header ────────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(brand),
                ) {
                    // Layer 1: hero image fills the box; absent / failed → brand color shows through
                    if (current.coverImageUrl != null) {
                        AsyncImage(
                            model = current.coverImageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Layer 2: bottom-to-top dark scrim for text legibility
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                                )
                            ),
                    )
                    // Layer 3: text content anchored bottom-left
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = if (lang == AppLanguage.KO)
                                "도시 전역 · ART EVENT · ${current.localizedLocationLabel(lang)}"
                            else
                                "CITY-WIDE · ART EVENT · ${current.localizedLocationLabel(lang)}",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = current.localizedName(lang),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${formatDateRange(current, lang)} · ${current.localizedLocationLabel(lang)}",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // ── About section ─────────────────────────────────────────────
            val description = current.localizedDescription(lang)
            if (description.isNotBlank()) {
                item {
                    SectionLabel(if (lang == AppLanguage.KO) "소개" else "ABOUT")
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // ── Participating Venues section ──────────────────────────────
            if (venues.isNotEmpty()) {
                item {
                    SectionLabel(if (lang == AppLanguage.KO) "참여 갤러리" else "PARTICIPATING VENUES")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        venues.forEach { venue ->
                            Box(
                                modifier = Modifier
                                    .border(1.dp, brand)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = venue,
                                    color = brand,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                }
            }

            // ── Exhibitions section ───────────────────────────────────────
            if (exhibitions.isNotEmpty()) {
                item {
                    SectionLabel(if (lang == AppLanguage.KO) "전시" else "EXHIBITIONS")
                }
                items(exhibitions, key = { it.id }) { exhibition ->
                    val treatment = current.let { evt ->
                        val localized = evt.localizedName(lang)
                        EventTreatment(
                            brandColor = brand,
                            label = if (localized.length > 20) localized.take(20) + "…" else localized,
                        )
                    }
                    ExhibitionCard(
                        exhibition = exhibition,
                        isBookmarked = exhibition.id in bookmarkedIds,
                        onBookmarkToggle = { onToggleBookmark(exhibition.id) },
                        onTap = { onExhibitionTap(exhibition) },
                        lang = lang,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        eventTreatment = treatment,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun formatDateRange(event: Event, lang: AppLanguage): String {
    val from = event.startDate
    val to = event.endDate
    return when (lang) {
        AppLanguage.KO -> "${from.year}.${from.monthNumber.toString().padStart(2, '0')}.${from.dayOfMonth.toString().padStart(2, '0')} – ${to.year}.${to.monthNumber.toString().padStart(2, '0')}.${to.dayOfMonth.toString().padStart(2, '0')}"
        AppLanguage.EN -> {
            val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
            "${months[from.monthNumber - 1]} ${from.dayOfMonth} – ${months[to.monthNumber - 1]} ${to.dayOfMonth}, ${to.year}"
        }
    }
}
