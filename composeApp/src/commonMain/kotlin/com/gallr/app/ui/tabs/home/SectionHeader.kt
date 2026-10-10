package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing

/**
 * A home section's header (DESIGN.md, Home tab): a bold `labelLarge` title that names what the section is, an
 * optional `bodySmall` line under it, and an optional trailing text action such as "모두 보기 ›".
 */
@Composable
internal fun SectionHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = GallrSpacing.screenMargin),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(GallrSpacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null) {
            Text(
                text = "$action ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier =
                    Modifier
                        .heightIn(min = ACTION_TARGET)
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(start = GallrSpacing.sm)
                        .wrapContentHeightCentered(),
            )
        }
    }
}

private fun Modifier.wrapContentHeightCentered(): Modifier = this.padding(vertical = (ACTION_TARGET - 18.dp) / 2)

private val ACTION_TARGET = 44.dp
