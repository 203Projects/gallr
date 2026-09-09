package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.CityWithCount
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.data.model.FilterState
import com.gallr.shared.data.model.MapDisplayMode
import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.model.RegionWithCount
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.ProfileNudgeRepository
import com.gallr.shared.repository.PromotionRepository
import com.gallr.shared.repository.ThemeRepository
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

sealed class ExhibitionListState {
    data object Loading : ExhibitionListState()

    data class Success(
        val exhibitions: List<Exhibition>,
    ) : ExhibitionListState()

    data class Error(
        val message: String,
    ) : ExhibitionListState()
}

/**
 * Lifecycle facade for the tab surfaces.
 *
 * Cohesive state and behavior live in catalog, filter, map, and promotion workflows. This facade
 * keeps the existing screen API stable while those surfaces migrate to immutable screen state.
 */
class TabsViewModel(
    exhibitionRepository: ExhibitionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val languageRepository: LanguageRepository,
    private val themeRepository: ThemeRepository,
    eventRepository: EventRepository,
    private val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.Anonymous),
    private val profileNudgeRepository: ProfileNudgeRepository = NoopProfileNudgeRepository,
    promotionRepository: PromotionRepository = NoopPromotionRepository,
    private val todayProvider: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
    nowMillisProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {
    private val log = AppLog.tagged("TabsViewModel")
    private val bookmarkMutationMutex = Mutex()

    val themeMode: StateFlow<ThemeMode> =
        themeRepository
            .observeThemeMode()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val language: StateFlow<AppLanguage> =
        languageRepository
            .observeLanguage()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.KO)

    private val catalog =
        CatalogWorkflow(
            scope = viewModelScope,
            exhibitionRepository = exhibitionRepository,
            eventRepository = eventRepository,
            todayProvider = todayProvider,
            nowMillisProvider = nowMillisProvider,
        )

    val featuredState: StateFlow<ExhibitionListState> = catalog.featuredState
    val allExhibitions: StateFlow<ExhibitionListState> = catalog.allExhibitions
    val isRefreshing: StateFlow<Boolean> = catalog.isRefreshing
    val activeEvents: StateFlow<List<Event>> = catalog.activeEvents
    val activeEventsById: StateFlow<Map<String, Event>> = catalog.activeEventsById

    val bookmarkedIds: StateFlow<Set<String>> =
        bookmarkRepository
            .observeBookmarkedIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val filters =
        FilterWorkflow(
            scope = viewModelScope,
            allExhibitions = allExhibitions,
            bookmarkedIds = bookmarkedIds,
            todayProvider = todayProvider,
        )

    val searchQuery: StateFlow<String> = filters.searchQuery
    val filterState: StateFlow<FilterState> = filters.filterState
    val selectedCity: StateFlow<String?> = filters.selectedCity
    val distinctCities: StateFlow<List<CityWithCount>> = filters.distinctCities
    val distinctRegions: StateFlow<List<RegionWithCount>> = filters.distinctRegions
    val tabExhibitionCount: StateFlow<Int> = filters.tabExhibitionCount
    val showMyListOnly: StateFlow<Boolean> = filters.showMyListOnly
    val filteredExhibitions: StateFlow<ExhibitionListState> = filters.filteredExhibitions

    private val promotion =
        PromotionWorkflow(
            scope = viewModelScope,
            selectedCity = selectedCity,
            promotionRepository = promotionRepository,
        )
    val promotedExhibition: StateFlow<PromotedExhibition?> = promotion.promotedExhibition

    private val map =
        MapWorkflow(
            scope = viewModelScope,
            allExhibitions = allExhibitions,
            bookmarkedIds = bookmarkedIds,
            language = language,
            activeEventsById = activeEventsById,
            todayProvider = todayProvider,
        )
    val mapDisplayMode: StateFlow<MapDisplayMode> = map.displayMode
    val myListMapPins: StateFlow<List<ExhibitionMapPin>> = map.myListPins
    val allMapPins: StateFlow<List<ExhibitionMapPin>> = map.allPins

    val listScreenState: StateFlow<ListScreenUiState> =
        combine(
            filteredExhibitions,
            filterState,
            bookmarkedIds,
            language,
            selectedCity,
            distinctCities,
            distinctRegions,
            tabExhibitionCount,
            showMyListOnly,
            searchQuery,
            isRefreshing,
            activeEvents,
            promotedExhibition,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            ListScreenUiState(
                exhibitions = values[0] as ExhibitionListState,
                filter = values[1] as FilterState,
                bookmarkedIds = values[2] as Set<String>,
                language = values[3] as AppLanguage,
                selectedCity = values[4] as String?,
                cities = values[5] as List<CityWithCount>,
                regions = values[6] as List<RegionWithCount>,
                tabExhibitionCount = values[7] as Int,
                showMyListOnly = values[8] as Boolean,
                searchQuery = values[9] as String,
                isRefreshing = values[10] as Boolean,
                activeEvents = values[11] as List<Event>,
                promotedExhibition = values[12] as PromotedExhibition?,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListScreenUiState())

    val mapScreenState: StateFlow<MapScreenUiState> =
        combine(
            mapDisplayMode,
            myListMapPins,
            allMapPins,
            language,
            activeEvents,
        ) { displayMode, myListPins, allPins, lang, events ->
            MapScreenUiState(
                displayMode = displayMode,
                myListPins = myListPins,
                allPins = allPins,
                language = lang,
                activeEvents = events,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapScreenUiState())

    private val _showSignUpNudge = MutableStateFlow(false)
    val showSignUpNudge: StateFlow<Boolean> = _showSignUpNudge
    private val signUpNudgeSuppressed = MutableStateFlow(false)

    init {
        catalog.loadInitial()
        observeActiveEventFilter()
        observeSignUpNudge()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeRepository.setThemeMode(mode) }
    }

    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch { languageRepository.setLanguage(lang) }
    }

    fun toggleLanguage() {
        setLanguage(if (language.value == AppLanguage.KO) AppLanguage.EN else AppLanguage.KO)
    }

    fun setSearchQuery(query: String) = filters.setSearchQuery(query)

    fun updateFilter(update: FilterState.() -> FilterState) = filters.updateFilter(update)

    fun toggleEventFilter(eventId: String) = filters.toggleEventFilter(eventId)

    fun setCity(cityKo: String?) = filters.setCity(cityKo)

    fun toggleRegion(regionKo: String) = filters.toggleRegion(regionKo)

    fun clearRegions() = filters.clearRegions()

    fun setShowMyListOnly(enabled: Boolean) = filters.setShowMyListOnly(enabled)

    fun clearAllFilters() = filters.clearAllFilters()

    fun setMapDisplayMode(mode: MapDisplayMode) = map.setDisplayMode(mode)

    fun toggleBookmark(
        exhibitionId: String,
        onCompleted: (saved: Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            bookmarkMutationMutex.withLock {
                runSuspendCatching {
                    val wasBookmarked = bookmarkRepository.isBookmarked(exhibitionId)
                    if (wasBookmarked) {
                        bookmarkRepository.removeBookmark(exhibitionId)
                    } else {
                        bookmarkRepository.addBookmark(exhibitionId)
                    }
                    !wasBookmarked
                }.onSuccess(onCompleted)
                    .onFailure { error -> log.warn("toggle_bookmark", error) }
            }
        }
    }

    fun clearAllBookmarks() {
        viewModelScope.launch { bookmarkRepository.clearAll() }
    }

    fun hideSignUpNudge() {
        signUpNudgeSuppressed.value = true
        _showSignUpNudge.value = false
    }

    fun dismissSignUpNudge() {
        _showSignUpNudge.value = false
        viewModelScope.launch {
            runSuspendCatching { profileNudgeRepository.setProfileNudgeShown() }
                .onFailure { log.warn("persist_profile_nudge", it) }
        }
    }

    fun findExhibitionById(id: String): Exhibition? = catalog.findExhibitionById(id)

    fun loadFeaturedExhibitions() = catalog.loadFeaturedExhibitions()

    fun loadAllExhibitions() = catalog.loadAllExhibitions()

    fun refresh() = catalog.refresh()

    fun refreshIfStale(maxAgeMillis: Long = FOREGROUND_CATALOG_MAX_AGE_MILLIS) {
        catalog.refreshIfStale(maxAgeMillis)
    }

    private fun observeActiveEventFilter() {
        viewModelScope.launch {
            activeEvents.collect { events ->
                filters.clearInactiveEvent(events.mapTo(mutableSetOf()) { it.id })
            }
        }
    }

    private fun observeSignUpNudge() {
        viewModelScope.launch {
            combine(
                bookmarkedIds,
                authState,
                profileNudgeRepository.observeProfileNudgeShown(),
                signUpNudgeSuppressed,
            ) { bookmarked, auth, nudgeShown, suppressed ->
                auth is AuthState.Anonymous &&
                    bookmarked.size >= SIGN_UP_NUDGE_THRESHOLD &&
                    !nudgeShown &&
                    !suppressed
            }.distinctUntilChanged()
                .collect { shouldShow -> _showSignUpNudge.value = shouldShow }
        }
    }

    companion object {
        fun factory(
            exhibitionRepository: ExhibitionRepository,
            bookmarkRepository: BookmarkRepository,
            languageRepository: LanguageRepository,
            themeRepository: ThemeRepository,
            eventRepository: EventRepository,
            authState: StateFlow<AuthState> = MutableStateFlow(AuthState.Anonymous),
            profileNudgeRepository: ProfileNudgeRepository = NoopProfileNudgeRepository,
            promotionRepository: PromotionRepository = NoopPromotionRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    TabsViewModel(
                        exhibitionRepository,
                        bookmarkRepository,
                        languageRepository,
                        themeRepository,
                        eventRepository,
                        authState,
                        profileNudgeRepository,
                        promotionRepository,
                    )
                }
            }

        private const val SIGN_UP_NUDGE_THRESHOLD = 5
        private const val FOREGROUND_CATALOG_MAX_AGE_MILLIS = 15 * 60 * 1_000L
    }
}

internal fun canonicalLocationKey(value: String): String = value.trim().lowercase()

internal fun preferredLocationLabel(values: List<String>): String =
    values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        .orEmpty()

private object NoopProfileNudgeRepository : ProfileNudgeRepository {
    override fun observeProfileNudgeShown() = flowOf(false)

    override suspend fun setProfileNudgeShown() = Unit
}

private object NoopPromotionRepository : PromotionRepository {
    override suspend fun getPromotedExhibition(
        cityKo: String,
        regionKo: String,
    ): Result<PromotedExhibition?> = Result.success(null)
}
