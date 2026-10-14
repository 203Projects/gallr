package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.BrowsedPublicRoute
import com.gallr.shared.route.PublicRouteBrowseQuery
import com.gallr.shared.route.PublicRouteDistrict
import com.gallr.shared.route.PublicRouteSort
import com.gallr.shared.route.appendPage
import com.gallr.shared.route.nextOffset
import com.gallr.shared.route.resolveDistrict
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** The rows of the 동선 둘러보기 screen (spec 092 data-model.md). */
sealed interface BrowseListState {
    /** The first page is being read: placeholder rows. */
    data object Loading : BrowseListState

    /** The first page could not be read: an error with a retry. */
    data object FirstPageError : BrowseListState

    /** No route is listed; [filtered] when a district was chosen, so clearing it may help. */
    data class Empty(
        val filtered: Boolean,
    ) : BrowseListState

    /**
     * [routes] in the server's order without repeats. [appending] while the next page is read; [appendFailed] when it
     * could not be, which offers a retry at the end of the list instead of reading it again on every scroll.
     */
    data class Loaded(
        val routes: List<BrowsedPublicRoute>,
        val hasMore: Boolean,
        val appending: Boolean = false,
        val appendFailed: Boolean = false,
    ) : BrowseListState
}

/** Where the list was scrolled to, restored when the reader comes back from a route's preview. */
data class ScrollAnchor(
    val index: Int = 0,
    val offset: Int = 0,
)

data class PublicRouteBrowseUiState(
    val query: PublicRouteBrowseQuery = PublicRouteBrowseQuery(),
    /** The district chips; empty while loading or after a failed read, which leaves 전체 alone. */
    val districts: List<PublicRouteDistrict> = emptyList(),
    val list: BrowseListState = BrowseListState.Loading,
    /** Today in Seoul, for the later-start label; null until the screen is opened. */
    val today: LocalDate? = null,
    val scrollAnchor: ScrollAnchor = ScrollAnchor(),
)

/**
 * Pages through every listed route for the 동선 둘러보기 screen (spec 092). The screen is opened from Home or Profile
 * through [open], which starts again from the top with the reader's last sort and district; returning from a
 * route's preview does not call it, so the rows and [ScrollAnchor] are as the reader left them.
 */
class PublicRouteBrowseViewModel(
    private val routeRepository: PersonalRouteRepository,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val log = AppLog.tagged("PublicRouteBrowse")
    private val mutableState = MutableStateFlow(PublicRouteBrowseUiState())
    val state: StateFlow<PublicRouteBrowseUiState> = mutableState.asStateFlow()

    /** Pages read for the current query; the next offset counts rows asked for, not rows kept. */
    private var pagesLoaded = 0
    private var pageJob: Job? = null
    private var districtJob: Job? = null

    fun open() {
        mutableState.update { it.copy(today = clock.todayIn(ROUTE_TIME_ZONE)) }
        reload()
        loadDistricts()
    }

    fun loadMore() {
        val list = mutableState.value.list as? BrowseListState.Loaded ?: return
        if (!list.hasMore || list.appending || list.appendFailed) return
        loadNextPage()
    }

    fun retry() {
        if (mutableState.value.list == BrowseListState.FirstPageError) reload()
    }

    fun retryMore() {
        val list = mutableState.value.list as? BrowseListState.Loaded ?: return
        if (list.appendFailed) loadNextPage()
    }

    fun selectSort(sort: PublicRouteSort) {
        val query = mutableState.value.query
        if (query.sort != sort) changeQuery(query.copy(sort = sort))
    }

    /** Chooses [districtKo]; choosing the selected district again clears the filter. */
    fun selectDistrict(districtKo: String) {
        val query = mutableState.value.query
        changeQuery(query.copy(districtKo = districtKo.takeIf { it != query.districtKo }))
    }

    fun clearDistrict() {
        val query = mutableState.value.query
        if (query.districtKo != null) changeQuery(query.copy(districtKo = null))
    }

    fun saveScrollAnchor(
        index: Int,
        offset: Int,
    ) {
        mutableState.update { it.copy(scrollAnchor = ScrollAnchor(index, offset)) }
    }

    private fun changeQuery(query: PublicRouteBrowseQuery) {
        mutableState.update { it.copy(query = query) }
        reload()
    }

    private fun reload() {
        pageJob?.cancel()
        pagesLoaded = 0
        mutableState.update { it.copy(list = BrowseListState.Loading, scrollAnchor = ScrollAnchor()) }
        val query = mutableState.value.query
        pageJob =
            viewModelScope.launch {
                val page =
                    routeRepository.browsePublic(query, offset = 0).getOrElse { error ->
                        log.warn("browse_page_load_failed", error)
                        mutableState.update { it.copy(list = BrowseListState.FirstPageError) }
                        return@launch
                    }
                pagesLoaded = 1
                val list =
                    if (page.routes.isEmpty()) {
                        BrowseListState.Empty(filtered = query.districtKo != null)
                    } else {
                        BrowseListState.Loaded(appendPage(emptyList(), page), page.hasMore)
                    }
                mutableState.update { it.copy(list = list) }
            }
    }

    private fun loadNextPage() {
        val query = mutableState.value.query
        val offset = nextOffset(pagesLoaded, PersonalRouteRepository.PUBLIC_ROUTE_PAGE_SIZE)
        updateLoaded { it.copy(appending = true, appendFailed = false) }
        pageJob =
            viewModelScope.launch {
                val page =
                    routeRepository.browsePublic(query, offset).getOrElse { error ->
                        log.warn("browse_page_load_failed", error)
                        updateLoaded { it.copy(appending = false, appendFailed = true) }
                        return@launch
                    }
                pagesLoaded += 1
                updateLoaded { loaded ->
                    loaded.copy(routes = appendPage(loaded.routes, page), hasMore = page.hasMore, appending = false)
                }
            }
    }

    private fun loadDistricts() {
        districtJob?.cancel()
        districtJob =
            viewModelScope.launch {
                val districts =
                    routeRepository.listPublicDistricts().getOrElse { error ->
                        // The chips stay empty; a failed read never clears the reader's district.
                        log.warn("browse_districts_load_failed", error)
                        mutableState.update { it.copy(districts = emptyList()) }
                        return@launch
                    }
                mutableState.update { it.copy(districts = districts) }
                val selected = mutableState.value.query.districtKo
                if (selected != null && resolveDistrict(selected, districts) == null) clearDistrict()
            }
    }

    private fun updateLoaded(change: (BrowseListState.Loaded) -> BrowseListState.Loaded) {
        mutableState.update { current ->
            val loaded = current.list as? BrowseListState.Loaded ?: return@update current
            current.copy(list = change(loaded))
        }
    }

    companion object {
        fun factory(routeRepository: PersonalRouteRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PublicRouteBrowseViewModel(routeRepository = routeRepository) }
            }
    }
}
