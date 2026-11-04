package com.gallr.app.ui.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gallr.shared.data.model.AppLanguage
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_more_horiz
import org.jetbrains.compose.resources.painterResource

/** The words of a 내 취향 card's ⋯ menu (spec 101 contracts/taste-ui.md "덜 보기"). */
internal data class RecommendationMenuCopy(
    val open: String,
    val save: String,
    val lessLikeThis: String,
    val share: String,
)

internal fun recommendationMenuCopy(
    language: AppLanguage,
    isBookmarked: Boolean,
): RecommendationMenuCopy =
    when (language) {
        AppLanguage.KO -> {
            RecommendationMenuCopy(
                open = "추천 메뉴",
                save = if (isBookmarked) "저장 취소" else "저장",
                lessLikeThis = "덜 보기",
                share = "공유",
            )
        }

        AppLanguage.EN -> {
            RecommendationMenuCopy(
                open = "Recommendation actions",
                save = if (isBookmarked) "UNSAVE" else "SAVE",
                lessLikeThis = "LESS LIKE THIS",
                share = "SHARE",
            )
        }
    }

/** The snackbar after 덜 보기: what happened and the way back; [hiddenState] is what a hidden card reads as. */
internal data class LessLikeThisSnackbarCopy(
    val message: String,
    val undo: String,
    val hiddenState: String,
)

internal fun lessLikeThisSnackbarCopy(language: AppLanguage): LessLikeThisSnackbarCopy =
    when (language) {
        AppLanguage.KO -> {
            LessLikeThisSnackbarCopy(message = "숨겼어요 · 종료일까지", undo = "되돌리기", hiddenState = "숨김 · 종료일까지")
        }

        AppLanguage.EN -> {
            LessLikeThisSnackbarCopy(
                message = "Hidden until it closes",
                undo = "UNDO",
                hiddenState = "Hidden until it closes",
            )
        }
    }

/**
 * The 덜 보기 snackbar on both screens. It stays a long while, and with a screen reader until it is acted on,
 * because the undo is otherwise hard to reach in time; the hidden card also carries the undo (see
 * [hiddenRecommendation]).
 */
internal suspend fun showLessLikeThisSnackbar(
    hostState: SnackbarHostState,
    language: AppLanguage,
    screenReaderActive: Boolean,
    onUndo: () -> Unit,
) {
    hostState.currentSnackbarData?.dismiss()
    val copy = lessLikeThisSnackbarCopy(language)
    val result =
        hostState.showSnackbar(
            message = copy.message,
            actionLabel = copy.undo,
            withDismissAction = screenReaderActive,
            duration = if (screenReaderActive) SnackbarDuration.Indefinite else SnackbarDuration.Long,
        )
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

/** A card hidden with 덜 보기: dimmed until the list is read again, and read aloud as hidden with its undo. */
internal fun Modifier.hiddenRecommendation(
    hidden: Boolean,
    language: AppLanguage,
    onUndo: () -> Unit,
): Modifier {
    if (!hidden) return this
    val copy = lessLikeThisSnackbarCopy(language)
    return alpha(HIDDEN_CARD_ALPHA).semantics {
        stateDescription = copy.hiddenState
        customActions =
            listOf(
                CustomAccessibilityAction(copy.undo) {
                    onUndo()
                    true
                },
            )
    }
}

/** The ⋯ on a 내 취향 card: save, 덜 보기 and share. Editorial cards do not carry it. */
@Composable
internal fun RecommendationOverflowMenu(
    isBookmarked: Boolean,
    language: AppLanguage,
    onBookmarkToggle: () -> Unit,
    onLessLikeThis: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onBackground,
) {
    val copy = recommendationMenuCopy(language, isBookmarked)
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(TARGET).semantics { contentDescription = copy.open },
        ) {
            Icon(painter = painterResource(Res.drawable.ic_more_horiz), contentDescription = null, tint = tint)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            DropdownMenuItem(
                text = { Text(copy.save) },
                onClick = {
                    open = false
                    onBookmarkToggle()
                },
            )
            DropdownMenuItem(
                text = { Text(copy.lessLikeThis) },
                onClick = {
                    open = false
                    onLessLikeThis()
                },
            )
            DropdownMenuItem(
                text = { Text(copy.share) },
                onClick = {
                    open = false
                    onShare()
                },
            )
        }
    }
}

/** A card the visitor just hid stays in the list until it is read again, at this opacity. */
private const val HIDDEN_CARD_ALPHA = 0.5f

private val TARGET = 44.dp
