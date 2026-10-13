package com.gallr.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.exhibitionStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** The card footer: the date range, and the run's status in the accent on the trailing edge when it has one. */
@Composable
internal fun ExhibitionDateRow(
    exhibition: Exhibition,
    lang: AppLanguage,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = exhibition.localizedDateRange(lang),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val statusLabel = exhibitionStatus(exhibition.openingDate, exhibition.closingDate, today).label(lang)
        if (statusLabel != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = GallrAccent.activeIndicator,
            )
        }
    }
}
