package com.gallr.app.ui.detail

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.PlatformBackHandler
import com.gallr.app.ShareHandler
import com.gallr.app.platform.decodeImageBitmap
import com.gallr.app.share.ExhibitionStoryCardPalette
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.SharePreviewState
import com.gallr.app.viewmodel.SharePreviewStateHolder
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePreviewScreen(
    exhibition: Exhibition,
    lang: AppLanguage,
    palette: ExhibitionStoryCardPalette,
    shareHandler: ShareHandler,
    onBack: () -> Unit,
    onShareSheetOpened: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var bitmap by remember(exhibition.id, lang, palette) { mutableStateOf<ImageBitmap?>(null) }
    val holder =
        remember(exhibition.id, lang, palette, shareHandler) {
            SharePreviewStateHolder(
                scope = scope,
                render = {
                    shareHandler.renderExhibitionStoryCard(exhibition, lang, palette).also {
                        bitmap = checkNotNull(decodeImageBitmap(it.pngBytes))
                    }
                },
                present = { card, onDismiss -> shareHandler.shareStoryCard(card, onDismiss, onShareSheetOpened) },
            )
        }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val state by holder.state.collectAsState()
    val goBack = {
        holder.close()
        onBack()
    }
    PlatformBackHandler(goBack)
    val colors = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (lang == AppLanguage.KO) "미리보기" else "Preview") },
                navigationIcon = {
                    IconButton(
                        onClick = goBack,
                        modifier =
                            Modifier.semantics {
                                contentDescription = if (lang == AppLanguage.KO) "뒤로" else "Back"
                            },
                    ) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = GallrSpacing.screenMargin)) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = GallrSpacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                val width = minOf(maxWidth, maxHeight * 9 / 16)
                Crossfade(state, animationSpec = tween(200), label = "sharePreview") { current ->
                    Box(
                        Modifier
                            .size(width, width * 16 / 9)
                            .background(colors.surfaceVariant)
                            .border(1.dp, colors.outlineVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (current) {
                            SharePreviewState.Rendering -> {
                                CircularProgressIndicator(color = colors.onBackground)
                            }

                            SharePreviewState.Failed -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(GallrSpacing.md),
                                ) {
                                    Text(
                                        if (lang == AppLanguage.KO) "이미지를 만들지 못했어요" else "Couldn't create the image",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurfaceVariant,
                                    )
                                    OutlinedButton(
                                        onClick = holder::retry,
                                        modifier = Modifier.height(44.dp),
                                        shape = RectangleShape,
                                    ) {
                                        Text(if (lang == AppLanguage.KO) "다시 시도" else "Retry")
                                    }
                                }
                            }

                            is SharePreviewState.Ready -> {
                                bitmap?.let {
                                    Image(
                                        it,
                                        current.card.shareDescriptor,
                                        Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = holder::share,
                enabled = (state as? SharePreviewState.Ready)?.sharing == false,
                modifier = Modifier.fillMaxWidth().padding(bottom = GallrSpacing.md).height(52.dp),
                shape = RectangleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colors.onBackground,
                        contentColor = colors.background,
                    ),
            ) {
                Text(if (lang == AppLanguage.KO) "공유하기" else "Share", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
