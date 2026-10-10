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

    /** The personal route composer over the device's draft (spec 089). */
    data object RouteComposer : AppDestination

    /** A listed route opened read-only from 추천 동선 (spec 089 US9). */
    data class PublicRoutePreview(
        val routeId: String,
    ) : AppDestination

    data class EditorDetail(
        val editorId: String,
    ) : AppDestination
}

@Stable
internal class AppNavigationState {
    private var galleryBackDestination: AppDestination = AppDestination.Tabs
    private var exhibitionBackDestination: AppDestination = AppDestination.Tabs
    private var routeComposerBackDestination: AppDestination = AppDestination.Tabs
    private var publicRouteBackDestination: AppDestination = AppDestination.Tabs
    private var routeRequestId = 0L

    // Where a flow that asked for sign-in waits; leaving the account screen returns there (spec 089 RO2).
    private var signInReturnDestination: AppDestination? = null

    var selectedTab by mutableIntStateOf(0)
        private set

    var destination by mutableStateOf<AppDestination>(AppDestination.Tabs)
        private set

    /**
     * What the MY tab should do once it is shown; null when nothing is pending. It is cleared through
     * [onMyTabRequestHandled], so showing the tab again later (after the composer, a detail page or another tab) does
     * not replay an old request such as reopening the account screen.
     */
    var myTabRequest by mutableStateOf<MyTabRequest?>(null)
        private set

    fun selectTab(index: Int) {
        selectedTab = index
        destination = AppDestination.Tabs
        signInReturnDestination = null
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

    fun showRouteComposer() {
        if (destination != AppDestination.RouteComposer) routeComposerBackDestination = destination
        destination = AppDestination.RouteComposer
        signInReturnDestination = null
    }

    fun returnFromRouteComposer() {
        destination = routeComposerBackDestination
    }

    fun showPublicRoute(routeId: String) {
        if (destination !is AppDestination.PublicRoutePreview) publicRouteBackDestination = destination
        destination = AppDestination.PublicRoutePreview(routeId)
    }

    fun returnFromPublicRoute() {
        destination = publicRouteBackDestination
    }

    fun showMyRoutes() {
        myTabRequest = MyTabRequest.MY_ROUTES
        selectTab(3)
    }

    fun onMyTabRequestHandled() {
        myTabRequest = null
    }

    fun showEditor(editorId: String) {
        destination = AppDestination.EditorDetail(editorId)
    }

    fun showTabs() {
        destination = AppDestination.Tabs
    }

    fun showSignIn() {
        val origin = destination
        myTabRequest = MyTabRequest.SIGN_IN
        selectTab(3)
        signInReturnDestination = origin.takeIf { it != AppDestination.Tabs }
    }

    /** Leaves a requested sign-in; true when the flow that asked for it is shown again. */
    fun returnFromSignIn(): Boolean {
        val origin = signInReturnDestination ?: return false
        signInReturnDestination = null
        destination = origin
        return true
    }

    fun showAddPastVisits() {
        myTabRequest = MyTabRequest.ADD_PAST_VISITS
        selectTab(3)
    }
}

/** A one-off request for the MY tab from elsewhere in the app. */
enum class MyTabRequest {
    /** A flow needs the author signed in, such as saving a route (spec 089 RR1). */
    SIGN_IN,

    /** Archive activation asks to add past visits. */
    ADD_PAST_VISITS,

    /** The route sheet's 모두 보기 asks for the 동선 section (spec 089 DD3). */
    MY_ROUTES,
}

@Composable
internal fun rememberAppNavigationState(): AppNavigationState = remember { AppNavigationState() }
