package com.gallr.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.RouteCurationMode

internal sealed interface AppDestination {
    data object Tabs : AppDestination

    data class ExhibitionDetail(
        val exhibition: Exhibition,
        val analyticsSuppressed: Boolean = false,
    ) : AppDestination

    data class GalleryDetail(
        val exhibition: Exhibition,
        val analyticsSuppressed: Boolean = false,
    ) : AppDestination

    data class EventDetail(
        val eventId: String,
    ) : AppDestination

    data object EditorSelector : AppDestination

    data object Settings : AppDestination

    data object Recommendations : AppDestination

    data class RoutePlanner(
        val origin: GeoPoint,
        val initialMode: RouteCurationMode,
        val requestId: Long,
    ) : AppDestination

    data class EditorDetail(
        val editorId: String,
    ) : AppDestination
}

@Stable
internal class AppNavigationState {
    private var galleryBackDestination: AppDestination = AppDestination.Tabs
    private var exhibitionBackDestination: AppDestination = AppDestination.Tabs
    private var routeRequestId = 0L

    var selectedTab by mutableIntStateOf(0)
        private set

    var destination by mutableStateOf<AppDestination>(AppDestination.Tabs)
        private set

    var addPastVisitsRequest by mutableIntStateOf(0)
        private set

    fun selectTab(index: Int) {
        selectedTab = index
        destination = AppDestination.Tabs
    }

    fun showExhibition(
        exhibition: Exhibition,
        analyticsSuppressed: Boolean = false,
        returnTo: AppDestination = AppDestination.Tabs,
    ) {
        exhibitionBackDestination = returnTo
        destination = AppDestination.ExhibitionDetail(exhibition, analyticsSuppressed)
    }

    fun returnFromExhibition() {
        destination = exhibitionBackDestination
    }

    fun showGallery(
        exhibition: Exhibition,
        analyticsSuppressed: Boolean = false,
    ) {
        galleryBackDestination = destination
        destination = AppDestination.GalleryDetail(exhibition, analyticsSuppressed)
    }

    fun returnFromGallery() {
        destination = galleryBackDestination
    }

    fun showEvent(eventId: String) {
        destination = AppDestination.EventDetail(eventId)
    }

    fun showEditorSelector() {
        destination = AppDestination.EditorSelector
    }

    fun showSettings() {
        destination = AppDestination.Settings
    }

    fun showRecommendations() {
        selectedTab = 0
        destination = AppDestination.Recommendations
    }

    fun showRoute(
        origin: GeoPoint,
        initialMode: RouteCurationMode = RouteCurationMode.NEIGHBORHOOD,
    ) {
        selectedTab = 2
        routeRequestId += 1
        destination = AppDestination.RoutePlanner(origin, initialMode, routeRequestId)
    }

    fun showEditor(editorId: String) {
        destination = AppDestination.EditorDetail(editorId)
    }

    fun showTabs() {
        destination = AppDestination.Tabs
    }

    fun showAddPastVisits() {
        addPastVisitsRequest += 1
        selectTab(3)
    }
}

@Composable
internal fun rememberAppNavigationState(): AppNavigationState = remember { AppNavigationState() }
