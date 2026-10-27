package com.gallr.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.recommendation.RecommendationEvidence

internal sealed interface AppDestination {
    data class ExhibitionDetail(
        val exhibition: Exhibition,
        val analyticsSuppressed: Boolean = false,
        /** Opened from the curator's-eye carousel: the detail screen scrolls to the note (spec 090 FR-012). */
        val scrollToNote: Boolean = false,
        /** The 내 취향 card's reasons when opened from it; empty from anywhere else (spec 101 FR-011). */
        val tasteReasons: List<RecommendationEvidence> = emptyList(),
        /** Opened from a 내 취향 card's 공유: the share preview shows first, and back returns to the page. */
        val openShare: Boolean = false,
    ) : AppDestination {
        /** A share from a card menu is not a visit to the page, so only a page opened to read it counts as a view. */
        val recordsSurfaceView: Boolean get() = !analyticsSuppressed && !openShare
    }

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

    /** Every listed route, opened from the Home rail or the Profile 추천 동선 section (spec 092). */
    data object PublicRouteBrowse : AppDestination

    data class EditorDetail(
        val editorId: String,
    ) : AppDestination

    /** A themed collection opened from the home tab. */
    data class HomeCollection(
        val collection: com.gallr.shared.home.HomeCollection,
    ) : AppDestination

    /** The account view: sign-in while anonymous, the profile once signed in (spec 094 R5). */
    data object Account : AppDestination
}

/** The four tabs, in bar order. */
internal enum class AppTab { HOME, LIST, MAP, MY }

/** One screen in a tab's trail; [id] is unique for the life of the state and keys the screen's saved state. */
internal data class NavEntry(
    val id: Long,
    val destination: AppDestination,
)

/** What the screen area shows: the selected tab's top screen, or the entry on top of its trail. */
internal data class VisibleScreen(
    val tab: AppTab,
    val entry: NavEntry?,
) {
    val destination: AppDestination? get() = entry?.destination

    /** Keys the screen's saved state; a tab's top screen keeps one key for the life of the app. */
    val key: String get() = entry?.let { screenKey(it.id) } ?: "tab-${tab.name}"
}

internal fun screenKey(entryId: Long): String = "entry-$entryId"

/**
 * Where the visitor is (spec 094): each tab keeps its own trail of screens over its top screen. Opening a screen
 * pushes onto the selected tab's trail, back pops it, switching tabs keeps every trail and choosing the selected tab
 * again returns it to its top. The tab bar is always on screen, so nothing here hides it.
 */
@Stable
internal class AppNavigationState {
    private val trails = AppTab.entries.associateWith { mutableStateListOf<NavEntry>() }
    private var nextEntryId = 0L
    private var routeRequestId = 0L

    /** Told which entries left their trail, so the screens' saved state can be removed. */
    var onEntriesDropped: (List<Long>) -> Unit = {}

    var selectedTab by mutableStateOf(AppTab.HOME)
        private set

    /**
     * What the MY tab should do once it is shown; null when nothing is pending. It is cleared through
     * [onMyTabRequestHandled], so showing the tab again later does not replay an old request.
     */
    var myTabRequest by mutableStateOf<MyTabRequest?>(null)
        private set

    fun trail(tab: AppTab): List<NavEntry> = trails.getValue(tab)

    /** The screen on top of the selected tab; null at the tab's top screen. */
    val currentEntry: NavEntry?
        get() = trails.getValue(selectedTab).lastOrNull()

    val destination: AppDestination?
        get() = currentEntry?.destination

    val canGoBack: Boolean
        get() = trails.getValue(selectedTab).isNotEmpty()

    val visibleScreen: VisibleScreen
        get() = VisibleScreen(selectedTab, currentEntry)

    /** Another tab is shown as it was left; the selected tab returns to its top (FR-006). */
    fun selectTab(tab: AppTab) {
        if (tab == selectedTab) clear(tab) else selectedTab = tab
    }

    /** Closes the screen on top of the selected tab; false at its top screen. */
    fun back(): Boolean {
        val dropped = trails.getValue(selectedTab).removeLastOrNull() ?: return false
        onEntriesDropped(listOf(dropped.id))
        return true
    }

    fun showExhibition(
        exhibition: Exhibition,
        analyticsSuppressed: Boolean = false,
        scrollToNote: Boolean = false,
        tasteReasons: List<RecommendationEvidence> = emptyList(),
        openShare: Boolean = false,
    ) = open(AppDestination.ExhibitionDetail(exhibition, analyticsSuppressed, scrollToNote, tasteReasons, openShare))

    fun showGallery(
        exhibition: Exhibition,
        analyticsSuppressed: Boolean = false,
    ) = open(AppDestination.GalleryDetail(exhibition, analyticsSuppressed))

    fun showEvent(eventId: String) = open(AppDestination.EventDetail(eventId))

    fun showEditorSelector() = open(AppDestination.EditorSelector)

    fun showSettings() = open(AppDestination.Settings)

    fun showRecommendations() = open(AppDestination.Recommendations)

    fun showRoute(
        origin: GeoPoint,
        initialMode: RouteCurationMode = RouteCurationMode.NEIGHBORHOOD,
    ) {
        routeRequestId += 1
        open(AppDestination.RoutePlanner(origin, initialMode, routeRequestId))
    }

    /**
     * There is one route draft, so the composer is open in at most one trail. Asking for it again goes to the tab
     * holding it and closes the screens above it, which is also how a share resumed after sign-in comes back.
     */
    fun showRouteComposer() {
        val holder =
            AppTab.entries.firstOrNull { tab ->
                trail(tab).any { it.destination == AppDestination.RouteComposer }
            }
        if (holder == null) {
            open(AppDestination.RouteComposer)
            return
        }
        selectedTab = holder
        val trail = trails.getValue(holder)
        val composerIndex = trail.indexOfLast { it.destination == AppDestination.RouteComposer }
        drop(trail, fromIndex = composerIndex + 1)
    }

    fun showPublicRoute(routeId: String) = open(AppDestination.PublicRoutePreview(routeId))

    fun showEditor(editorId: String) = open(AppDestination.EditorDetail(editorId))

    fun showCollection(collection: com.gallr.shared.home.HomeCollection) =
        open(AppDestination.HomeCollection(collection))

    /** Sign-in, or the profile once signed in, over the screen that asked; back returns to it (spec 094 R5). */
    fun showAccount() = open(AppDestination.Account)

    /** 동선 둘러보기 (spec 092): every listed route, over the screen that asked; back returns to it. */
    fun showPublicRouteBrowse() = open(AppDestination.PublicRouteBrowse)

    /** The route sheet's 모두 보기: the 동선 section at MY's top. */
    fun showMyRoutes() = requestMyTab(MyTabRequest.MY_ROUTES)

    /** Archive activation: adding past visits at MY's top. */
    fun showAddPastVisits() = requestMyTab(MyTabRequest.ADD_PAST_VISITS)

    fun onMyTabRequestHandled() {
        myTabRequest = null
    }

    private fun requestMyTab(request: MyTabRequest) {
        myTabRequest = request
        selectedTab = AppTab.MY
        clear(AppTab.MY)
    }

    private fun open(destination: AppDestination) {
        val trail = trails.getValue(selectedTab)
        if (trail.lastOrNull()?.destination == destination) return
        trail += NavEntry(id = nextEntryId++, destination = destination)
    }

    private fun clear(tab: AppTab) = drop(trails.getValue(tab), fromIndex = 0)

    /** Removes the entries from [fromIndex] on, newest first, and reports them. */
    private fun drop(
        trail: MutableList<NavEntry>,
        fromIndex: Int,
    ) {
        if (fromIndex >= trail.size) return
        val dropped = trail.subList(fromIndex, trail.size).reversed()
        repeat(dropped.size) { trail.removeAt(trail.lastIndex) }
        onEntriesDropped(dropped.map(NavEntry::id))
    }
}

/** A one-off request for the MY tab from elsewhere in the app. */
enum class MyTabRequest {
    /** Archive activation asks to add past visits. */
    ADD_PAST_VISITS,

    /** The route sheet's 모두 보기 asks for the 동선 section (spec 089 DD3). */
    MY_ROUTES,
}

@Composable
internal fun rememberAppNavigationState(): AppNavigationState = remember { AppNavigationState() }
