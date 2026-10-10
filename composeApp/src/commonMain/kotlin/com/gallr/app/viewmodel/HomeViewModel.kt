package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.home.HomeFeed
import com.gallr.shared.home.buildHomeFeed
import com.gallr.shared.repository.FollowedGalleryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

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
    private val todayProvider: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    // Building the feed reads every exhibition's text for art terms, so it runs off the main thread.
    val state: StateFlow<HomeUiState> =
        combine(
            exhibitionsState,
            featuredState,
            followedGalleryRepository.observeFollowedGalleries(),
            authState,
        ) { exhibitions, featured, followed, auth ->
            homeUiState(exhibitions, featured, followed, auth, todayProvider())
        }.flowOn(backgroundDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    companion object {
        fun factory(
            exhibitionsState: StateFlow<ExhibitionListState>,
            featuredState: StateFlow<ExhibitionListState>,
            followedGalleryRepository: FollowedGalleryRepository,
            authState: StateFlow<AuthState>,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    HomeViewModel(exhibitionsState, featuredState, followedGalleryRepository, authState)
                }
            }
    }
}

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
            ),
        greetingName =
            (auth as? AuthState.Authenticated)
                ?.user
                ?.displayName
                ?.trim()
                ?.takeIf(String::isNotEmpty),
    )
}
