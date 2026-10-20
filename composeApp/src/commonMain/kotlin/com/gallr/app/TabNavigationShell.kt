package com.gallr.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
                    modifier = Modifier.testTag(TAB_BAR_TEST_TAG),
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) { content() }
    }
}

/**
 * Shows [AppNavigationState.visibleScreen] with a 150ms crossfade, each screen under its own saved-state key, so a
 * screen left by switching tabs comes back with its scroll positions and saveable values (spec 094 FR-005). Entries
 * that leave their trail have their saved state removed.
 */
@Composable
internal fun TabScreenHost(
    navigation: AppNavigationState,
    modifier: Modifier = Modifier,
    screen: @Composable (VisibleScreen) -> Unit,
) {
    val screenStates = rememberSaveableStateHolder()
    SideEffect {
        navigation.onEntriesDropped = { ids -> ids.forEach { id -> screenStates.removeState(screenKey(id)) } }
    }
    AnimatedContent(
        targetState = navigation.visibleScreen,
        modifier = modifier,
        contentKey = VisibleScreen::key,
        transitionSpec = {
            fadeIn(animationSpec = tween(SCREEN_CROSSFADE_MILLIS)) togetherWith
                fadeOut(animationSpec = tween(SCREEN_CROSSFADE_MILLIS))
        },
        label = "screenTransition",
    ) { visible ->
        screenStates.SaveableStateProvider(visible.key) { screen(visible) }
    }
}

private const val SCREEN_CROSSFADE_MILLIS = 150
