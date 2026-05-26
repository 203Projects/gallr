package com.gallr.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.gallr.app.share.ExhibitionStoryShareContent
import com.gallr.app.share.ExhibitionStorySharePreviewText
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage

private val PreviewHeaderColor = Color(0xFF0A0A0A)
private val PreviewStageColor = Color(0xFF111111)
private val PreviewCtaColor = Color(0xFF1A73E8)

@Composable
fun SharePreviewDialog(
    content: ExhibitionStoryShareContent,
    lang: AppLanguage,
    isSharing: Boolean,
    onCancel: () -> Unit,
    onShare: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PreviewStageColor),
        ) {
            SharePreviewHeader(
                lang = lang,
                isSharing = isSharing,
                onCancel = onCancel,
                onShare = onShare,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(PreviewStageColor)
                    .padding(GallrSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                StoryCardPreview(
                    content = content,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
            SharePreviewFooter(
                lang = lang,
                isSharing = isSharing,
                onShare = onShare,
            )
        }
    }
}

@Composable
private fun SharePreviewHeader(
    lang: AppLanguage,
    isSharing: Boolean,
    onCancel: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PreviewHeaderColor)
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = GallrSpacing.screenMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewTextButton(
            text = ExhibitionStorySharePreviewText.cancel(lang),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            enabled = !isSharing,
            onClick = onCancel,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = ExhibitionStorySharePreviewText.title(lang),
            style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
        )
        Spacer(Modifier.weight(1f))
        PreviewTextButton(
            text = ExhibitionStorySharePreviewText.share(lang),
            color = PreviewCtaColor,
            enabled = !isSharing,
            onClick = onShare,
        )
    }
}

@Composable
private fun StoryCardPreview(
    content: ExhibitionStoryShareContent,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val cardWidth = maxWidth * 0.55f
        Surface(
            modifier = Modifier
                .width(cardWidth)
                .aspectRatio(9f / 16f)
                .shadow(8.dp, RoundedCornerShape(6.dp)),
            color = Color.Black,
            shape = RoundedCornerShape(6.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF0A0A0A)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (content.coverImageUrl == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        AsyncImage(
                            model = content.coverImageUrl,
                            contentDescription = content.shareDescriptor,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = content.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = content.venue,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = content.dateRange,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "gallr",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun SharePreviewFooter(
    lang: AppLanguage,
    isSharing: Boolean,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PreviewHeaderColor)
            .navigationBarsPadding()
            .height(52.dp)
            .padding(horizontal = GallrSpacing.screenMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "g",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(GallrSpacing.sm))
        Text(
            text = if (lang == AppLanguage.KO) "공유 대상 선택" else "Choose destination",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (isSharing) PreviewCtaColor.copy(alpha = 0.45f) else PreviewCtaColor)
                .clickable(enabled = !isSharing, onClick = onShare)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (isSharing) {
                    ExhibitionStorySharePreviewText.loading(lang)
                } else {
                    ExhibitionStorySharePreviewText.send(lang)
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun PreviewTextButton(
    text: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (enabled) color else color.copy(alpha = 0.35f),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}
