package com.gallr.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.gallr.app.ui.components.GallrNavigationBar
import com.gallr.shared.data.model.AppLanguage

/** The tab bar steps aside while the keyboard is open and is on screen otherwise (spec 094 FR-001, FR-011). */
internal fun isTabBarVisible(imeBottomPx: Int): Boolean = imeBottomPx == 0

internal const val TAB_BAR_TEST_TAG = "gallr-tab-bar"

/**
 * Every screen sits in [content] above the tab bar (spec 094). The bar's height, its system navigation inset
 * included, is consumed here, so a screen's own insets and its bottom action bar end directly above the bar
 * without knowing it exists. While the keyboard is open the bar is not laid out and screens see the keyboard inset.
 */
@Composable
internal fun TabNavigationShell(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    lang: AppLanguage,
    imeBottomPx: Int,
    modifier: Modifier = Modifier,
    folds: List<BrowseFold> = emptyList(),
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (isTabBarVisible(imeBottomPx)) {
                GallrNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    lang = lang,
                    folds = folds,
                    modifier = Modifier.testTag(TAB_BAR_TEST_TAG),
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) { content() }
    }
}

internal data class BrowsePanePresentation(
    val visible: Boolean = true,
    val selectingFromMaster: Boolean = false,
)

internal val LocalBrowsePanePresentation = staticCompositionLocalOf { BrowsePanePresentation() }

/**
 * List and Map roots stay in the master slot while their detail is open. The selected detail always
 * stays in the primary slot, so changing the pane frames does not create a new screen instance.
 */
@Composable
internal fun TabScreenHost(
    navigation: AppNavigationState,
    modifier: Modifier = Modifier,
    folds: List<BrowseFold> = emptyList(),
    screen: @Composable (VisibleScreen) -> Unit,
) {
    val screenStates = key(navigation) { rememberSaveableStateHolder() }
    DisposableEffect(navigation, screenStates) {
        val dropScreenStates: (List<Long>) -> Unit = { ids ->
            ids.forEach { id -> screenStates.removeState(screenKey(id)) }
        }
        navigation.onEntriesDropped = dropScreenStates
        onDispose {
            if (navigation.onEntriesDropped === dropScreenStates) {
                navigation.onEntriesDropped = {}
            }
        }
    }
    val current = navigation.visibleScreen
    val master = if (current.tab.supportsBrowseMaster) VisibleScreen(current.tab, entry = null) else null
    val primary = current.takeUnless { it == master }
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val limits =
        with(density) {
            BrowsePaneLimits(
                expandedWidth = 600.dp.roundToPx(),
                sideWidth = 240.dp.roundToPx(),
                stackHeight = 160.dp.roundToPx(),
                masterWidths = 280.dp.roundToPx()..420.dp.roundToPx(),
            )
        }

    BoxWithConstraints(
        modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow().round() },
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val viewport = IntRect(origin.x, origin.y, origin.x + width, origin.y + height)
        val frames =
            browsePaneFrames(
                viewport = viewport,
                folds = folds,
                detailSupportsSplit = master != null && current.destination.supportsBrowseSplit,
                limits = limits,
            )
        val masterFrame = if (primary == null) frames.primary else frames.master
        // An empty primary can still hold an outgoing detail during the existing crossfade.
        val primaryFrame = frames.primary
        Layout(
            content = {
                Box(Modifier.clipToBounds().consumeWindowInsets(paneInsets(masterFrame, width, height))) {
                    BrowseScreenTransition(master) { visible ->
                        CompositionLocalProvider(
                            LocalBrowsePanePresentation provides
                                BrowsePanePresentation(
                                    visible = masterFrame != null && master == visible,
                                    selectingFromMaster = frames.master != null && master == visible,
                                ),
                        ) {
                            screenStates.SaveableStateProvider(visible.key) { screen(visible) }
                        }
                    }
                }
                Box(Modifier.clipToBounds().consumeWindowInsets(paneInsets(primaryFrame, width, height))) {
                    BrowseScreenTransition(primary) { visible ->
                        CompositionLocalProvider(
                            LocalBrowsePanePresentation provides BrowsePanePresentation(visible = primary == visible),
                        ) {
                            screenStates.SaveableStateProvider(visible.key) { screen(visible) }
                        }
                    }
                }
            },
        ) { children, _ ->
            val masterSize = masterFrame ?: frames.primary
            val masterContent = children[0].measure(Constraints.fixed(masterSize.width, masterSize.height))
            val primaryContent = children[1].measure(Constraints.fixed(primaryFrame.width, primaryFrame.height))
            layout(width, height) {
                masterFrame?.let { masterContent.place(it.left, it.top) }
                primaryContent.place(primaryFrame.left, primaryFrame.top)
            }
        }
    }
}

@Composable
private fun paneInsets(
    frame: IntRect?,
    width: Int,
    height: Int,
): WindowInsets {
    val insideEdges =
        buildList {
            if (frame != null) {
                if (frame.left > 0) add(WindowInsetsSides.Left)
                if (frame.top > 0) add(WindowInsetsSides.Top)
                if (frame.right < width) add(WindowInsetsSides.Right)
                if (frame.bottom < height) add(WindowInsetsSides.Bottom)
            }
        }.reduceOrNull { first, second -> first + second }
    return if (insideEdges == null) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing.only(insideEdges)
}

@Composable
private fun BrowseScreenTransition(
    target: VisibleScreen?,
    screen: @Composable (VisibleScreen) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        contentKey = { it?.key },
        transitionSpec = {
            fadeIn(animationSpec = tween(SCREEN_CROSSFADE_MILLIS)) togetherWith
                fadeOut(animationSpec = tween(SCREEN_CROSSFADE_MILLIS))
        },
        label = "screenTransition",
    ) { visible ->
        if (visible != null) screen(visible)
    }
}

private const val SCREEN_CROSSFADE_MILLIS = 150
