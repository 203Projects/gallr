package com.gallr.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing

/**
 * A section's header on any screen (DESIGN.md, Visual system): a bold `titleMedium` title that names what the section
 * is, an optional `bodySmall` line under it, and an optional trailing text action such as "모두 보기 ›". Home's
 * sections run edge to edge and take the screen margin here; a section inside an already padded column passes 0dp.
 */
@Composable
internal fun SectionHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    horizontalPadding: Dp = GallrSpacing.screenMargin,
    /**
     * While the subtitle is still being worked out: a sample of it, drawn invisibly under a skeleton bar so the held
     * line has the real line's height (Hangul and Latin lines differ).
     */
    subtitlePlaceholder: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
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
            } else if (subtitlePlaceholder != null) {
                Spacer(Modifier.height(GallrSpacing.xs))
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.clearAndSetSemantics { }) {
                    Text(
                        text = subtitlePlaceholder,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0f),
                    )
                    Box(
                        Modifier
                            .width(SUBTITLE_SKELETON_WIDTH)
                            .height(SUBTITLE_SKELETON_HEIGHT)
                            .skeleton(
                                rememberSkeletonPulse(),
                                MaterialTheme.colorScheme.onBackground,
                                GallrShapeTokens.small,
                            ),
                    )
                }
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
private val SUBTITLE_SKELETON_WIDTH = 200.dp
private val SUBTITLE_SKELETON_HEIGHT = 10.dp
