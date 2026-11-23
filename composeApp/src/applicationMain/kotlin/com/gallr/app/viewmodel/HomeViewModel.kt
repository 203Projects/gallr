package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.home.HOME_ROUTES_LIMIT
import com.gallr.shared.home.HomeFeed
import com.gallr.shared.home.buildHomeFeed
import com.gallr.shared.home.routesRail
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.CuratorNoteRepository
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.MostViewedExhibitionRepository
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

private val homeLog = AppLog.tagged("HomeViewModel")

sealed interface HomeUiState {
    data object Loading : HomeUiState

    /** The catalogue could not be loaded; [message] is `network` or `server`, as the catalogue reports it. */
    data class Error(
        val message: String,
    ) : HomeUiState

    data class Ready(
        val feed: HomeFeed,
        /** The signed-in visitor's display name for the greeting, null when anonymous or unnamed. */
        val greetingName: String?,
    ) : HomeUiState
}

/**
 * The home tab's state: the shared feed built from the catalogue the tabs already hold, the visitor's follows
 * and the session, recomputed whenever any of them changes. The For You rail reads [LocalDiscoveryViewModel]
 * directly, since that list is ranked on its own schedule.
 */
class HomeViewModel(
    exhibitionsState: StateFlow<ExhibitionListState>,
    featuredState: StateFlow<ExhibitionListState>,
    followedGalleryRepository: FollowedGalleryRepository,
    authState: StateFlow<AuthState>,
    private val curatorNoteRepository: CuratorNoteRepository,
    private val routeRepository: PersonalRouteRepository,
    private val mostViewedRepository: MostViewedExhibitionRepository,
    private val todayProvider: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** False hides the routes rail and skips its reads (`ReleaseFeatures.ROUTES`). */
    private val routesEnabled: Boolean = true,
) : ViewModel() {
    // Curator notes, public routes and most viewed. They are read on the first visit to Home and again on each
    // refresh, not on every return to the tab. Each section starts empty, so the feed shows as soon as the
    // catalogue has loaded and each section fills in when its read lands; a failed read keeps what the section
    // showed before (nothing, on the first read).
    private val editorialReads = MutableStateFlow(EditorialReads())
    private var editorialReadsJob: Job? = null

    // Building the feed reads every exhibition's text for art terms, so it runs off the main thread.
    val state: StateFlow<HomeUiState> =
        combine(
            exhibitionsState,
            featuredState,
            followedGalleryRepository.observeFollowedGalleries(),
            authState,
            editorialReads,
        ) { exhibitions, featured, followed, auth, reads ->
            homeUiState(exhibitions, featured, followed, auth, todayProvider(), reads)
        }.flowOn(backgroundDispatcher)
            .onStart { if (editorialReadsJob == null) refreshEditorialReads() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    /** Reads the editorial sections again; Home's pull-to-refresh and retry call it. A read already running is kept. */
    fun refreshEditorialReads() {
        if (editorialReadsJob?.isActive == true) return
        editorialReadsJob =
            viewModelScope.launch {
                launch { readCuratorNotes()?.let { notes -> editorialReads.update { it.copy(notes = notes) } } }
                if (routesEnabled) {
                    launch { readPublicRoutes()?.let { routes -> editorialReads.update { it.copy(routes = routes) } } }
                }
                launch { readMostViewed()?.let { ids -> editorialReads.update { it.copy(mostViewedIds = ids) } } }
            }
    }

    // Approved notes; null when the read fails.
    private suspend fun readCuratorNotes(): List<CuratorNote>? =
        curatorNoteRepository.getPublishedNotes().getOrElse { error ->
            homeLog.warn("curator_notes_load_failed", error)
            null
        }

    // The public ranking; null when it fails, which hides the rail (spec 091). Each shown route's stops are then read
    // together for the covers on its card; a route whose stops fail keeps its card.
    private suspend fun readPublicRoutes(): RankedRoutes? {
        val ranked =
            routeRepository.listPublic(limit = HOME_ROUTES_LIMIT).getOrElse { error ->
                homeLog.warn("home_routes_load_failed", error)
                return null
            }
        val stops =
            coroutineScope {
                routesRail(ranked)
                    .map { route ->
                        async {
                            val read =
                                routeRepository.loadPublicStops(route.id).getOrElse { error ->
                                    homeLog.warn("home_route_stops_load_failed", error)
                                    null
                                }
                            read?.let { route.id to it.route.stops.map { stop -> stop.exhibitionId } }
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .toMap()
            }
        return RankedRoutes(summaries = ranked, stopExhibitionIds = stops)
    }

    // What visitors opened most this week; null when the read fails, which hides the tile.
    private suspend fun readMostViewed(): List<String>? =
        mostViewedRepository.mostViewedIds().getOrElse { error ->
            homeLog.warn("home_most_viewed_load_failed", error)
            null
        }

    companion object {
        fun factory(
            exhibitionsState: StateFlow<ExhibitionListState>,
            featuredState: StateFlow<ExhibitionListState>,
            followedGalleryRepository: FollowedGalleryRepository,
            authState: StateFlow<AuthState>,
            curatorNoteRepository: CuratorNoteRepository,
            routeRepository: PersonalRouteRepository,
            mostViewedRepository: MostViewedExhibitionRepository,
            routesEnabled: Boolean = true,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    HomeViewModel(
                        exhibitionsState,
                        featuredState,
                        followedGalleryRepository,
                        authState,
                        curatorNoteRepository,
                        routeRepository,
                        mostViewedRepository,
                        routesEnabled = routesEnabled,
                    )
                }
            }
    }
}

/** The public ranking's rows and, per route id, its stops' exhibition ids in stop order. */
internal data class RankedRoutes(
    val summaries: List<PublicRouteSummary> = emptyList(),
    val stopExhibitionIds: Map<String, List<String>> = emptyMap(),
)

/** The editorial reads, carried together so the feed combine stays at five inputs. */
internal data class EditorialReads(
    val notes: List<CuratorNote> = emptyList(),
    val routes: RankedRoutes = RankedRoutes(),
    val mostViewedIds: List<String> = emptyList(),
)

/**
 * The catalogue decides the state: loading until it arrives, its error when it fails, otherwise the feed.
 * The featured list keeps the hero's editorial order when it has loaded; until then the hero falls back to
 * the catalogue's own featured exhibitions, so a slow second request never blanks the top of the screen.
 */
internal fun homeUiState(
    exhibitions: ExhibitionListState,
    featured: ExhibitionListState,
    followedGalleries: List<FollowedGallery>,
    auth: AuthState,
    today: LocalDate,
    reads: EditorialReads = EditorialReads(),
): HomeUiState {
    val catalogue =
        when (exhibitions) {
            is ExhibitionListState.Loading -> return HomeUiState.Loading
            is ExhibitionListState.Error -> return HomeUiState.Error(exhibitions.message)
            is ExhibitionListState.Success -> exhibitions.exhibitions.filter { it.isVisibleInCatalog(today) }
        }
    val hero =
        (featured as? ExhibitionListState.Success)?.exhibitions?.filter { it.isVisibleInCatalog(today) }
            ?: catalogue.filter(Exhibition::isFeatured)
    return HomeUiState.Ready(
        feed =
            buildHomeFeed(
                exhibitions = catalogue,
                featured = hero,
                today = today,
                followedGalleries = followedGalleries,
                curatorNotes = reads.notes,
                routes = reads.routes.summaries,
                routeStops = reads.routes.stopExhibitionIds,
                popularExhibitionIds = reads.mostViewedIds,
            ),
        greetingName =
            (auth as? AuthState.Authenticated)
                ?.user
                ?.displayName
                ?.trim()
                ?.takeIf(String::isNotEmpty),
    )
}
