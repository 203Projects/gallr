package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.util.parseHexColor

@Composable
fun EventPromotionCard(
    event: Event,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
    val name = event.localizedName(lang)
    val eyebrow = if (lang == AppLanguage.KO) "지금 진행 중 · ART EVENT" else "NOW ON · ART EVENT"
    val meta = "${event.localizedDateRange(lang)} · ${event.localizedLocationLabel(lang)}"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(brand)
            .border(1.dp, Color.Black)
            .clickable(onClick = onTap),
    ) {
        // Layer 1: hero image fills the box; absent / failed → brand color shows through
        if (event.coverImageUrl != null) {
            AsyncImage(
                model = event.coverImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }

        // Layer 2: bottom-to-top dark scrim for text legibility
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    )
                ),
        )

        // Layer 3: text content — fills width and height; Box wraps around this content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Text(
                text = eyebrow,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun Event.localizedDateRange(lang: AppLanguage): String {
    val from = startDate
    val to = endDate
    return when (lang) {
        AppLanguage.KO -> "${from.year}.${from.monthNumber.toString().padStart(2, '0')}.${from.dayOfMonth.toString().padStart(2, '0')} – ${to.year}.${to.monthNumber.toString().padStart(2, '0')}.${to.dayOfMonth.toString().padStart(2, '0')}"
        AppLanguage.EN -> {
            val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
            "${months[from.monthNumber - 1]} ${from.dayOfMonth} – ${months[to.monthNumber - 1]} ${to.dayOfMonth}, ${to.year}"
        }
    }
}
