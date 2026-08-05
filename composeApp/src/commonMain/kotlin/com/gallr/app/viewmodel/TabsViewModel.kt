package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.data.model.CityWithCount
import com.gallr.shared.data.model.FilterState
import com.gallr.shared.data.model.MapDisplayMode
import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.model.RegionWithCount
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.data.model.toMapPin
import com.gallr.shared.data.model.Event
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.ProfileNudgeRepository
import com.gallr.shared.repository.PromotionRepository
import com.gallr.shared.repository.ThemeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

sealed class ExhibitionListState {
    data object Loading : ExhibitionListState()
    data class Success(val exhibitions: List<Exhibition>) : ExhibitionListState()
    data class Error(val message: String) : ExhibitionListState()
}

class TabsViewModel(
    private val exhibitionRepository: ExhibitionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val languageRepository: LanguageRepository,
    private val themeRepository: ThemeRepository,
    private val eventRepository: EventRepository,
    private val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.Anonymous),
    private val profileNudgeRepository: ProfileNudgeRepository = NoopProfileNudgeRepository,
    private val promotionRepository: PromotionRepository = NoopPromotionRepository,
    private val todayProvider: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
    private val nowMillisProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    // ── Theme ─────────────────────────────────────────────────────────────────

    val themeMode: StateFlow<ThemeMode> =
        themeRepository.observeThemeMode()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeRepository.setThemeMode(mode) }
    }

    // ── Language ──────────────────────────────────────────────────────────────

    val language: StateFlow<AppLanguage> =
        languageRepository.observeLanguage()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.KO)

    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch { languageRepository.setLanguage(lang) }
    }

    fun toggleLanguage() {
        val current = language.value
        setLanguage(if (current == AppLanguage.KO) AppLanguage.EN else AppLanguage.KO)
    }

    // ── Raw data ────────────────────────────────────────────────────────────

    private val _featuredState =
        MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
    val featuredState: StateFlow<ExhibitionListState> = _featuredState

    private val _allExhibitions =
        MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)

    /**
     * Unfiltered exhibition list. Exposed so other ViewModels (e.g.
     * EditorSelectorViewModel, EditorDetailViewModel) can join against
     * the full set, not the user's current filter selection.
     */
    val allExhibitions: StateFlow<ExhibitionListState> = _allExhibitions

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private var activeLoadCount = 0
    private var featuredLoadInProgress = false
    private var allExhibitionsLoadInProgress = false
    private var lastSuccessfulCatalogLoadAtMillis: Long? = null

    // ── Active event ─────────────────────────────────────────────────────────

    private val _activeEvents = MutableStateFlow<List<Event>>(emptyList())
    val activeEvents: StateFlow<List<Event>> = _activeEvents

    private val _activeEventsById = MutableStateFlow<Map<String, Event>>(emptyMap())
    val activeEventsById: StateFlow<Map<String, Event>> = _activeEventsById

    private fun loadActiveEvents() {
        viewModelScope.launch {
            eventRepository.getActiveEvents()
                .onSuccess { events ->
                    _activeEventsById.value = events.associateBy { it.id }
                    _activeEvents.value = events
                }
                .onFailure {
                    println("ERROR [TabsViewModel] loadActiveEvents: ${it.message}")
                    _activeEventsById.value = emptyMap()
                    _activeEvents.value = emptyList()
                }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ── Filter state ────────────────────────────────────────────────────────

    private val _filterState = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = _filterState

    fun updateFilter(update: FilterState.() -> FilterState) {
        _filterState.value = _filterState.value.update()
    }

    fun toggleEventFilter(eventId: String) {
        _filterState.value = _filterState.value.let { current ->
            current.copy(
                selectedEventId = if (current.selectedEventId == eventId) null else eventId,
            )
        }
    }

    // ── City filter ──────────────────────────────────────────────────────────

    private val _selectedCity = MutableStateFlow<String?>(null) // null = all cities, otherwise cityKo
    val selectedCity: StateFlow<String?> = _selectedCity

    fun setCity(cityKo: String?) {
        _selectedCity.value = cityKo
        _filterState.value = _filterState.value.copy(regions = emptyList())
    }

    val distinctCities: StateFlow<List<CityWithCount>> =
        _allExhibitions.map { state ->
            val today = todayProvider()
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.isVisibleInCatalog(today) }
                ?.groupBy { canonicalLocationKey(it.cityKo) }
                ?.mapNotNull { (_, exhibitions) ->
                    val cityKo = preferredLocationLabel(exhibitions.map { it.cityKo })
                    if (cityKo.isEmpty()) return@mapNotNull null
                    CityWithCount(
                        cityKo = cityKo,
                        cityEn = preferredLocationLabel(exhibitions.map { it.cityEn }),
                        count = exhibitions.size,
                    )
                }
                ?.sortedByDescending { it.count }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val distinctRegions: StateFlow<List<RegionWithCount>> =
        combine(_allExhibitions, _selectedCity) { state, city ->
            if (city == null) return@combine emptyList()
            val today = todayProvider()
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter {
                    canonicalLocationKey(it.cityKo) == canonicalLocationKey(city) &&
                        it.isVisibleInCatalog(today)
                }
                ?.groupBy { canonicalLocationKey(it.regionKo) }
                ?.mapNotNull { (_, exhibitions) ->
                    val regionKo = preferredLocationLabel(exhibitions.map { it.regionKo })
                    if (regionKo.isEmpty()) return@mapNotNull null
                    RegionWithCount(
                        regionKo = regionKo,
                        regionEn = preferredLocationLabel(exhibitions.map { it.regionEn }),
                        count = exhibitions.size,
                    )
                }
                ?.sortedByDescending { it.count }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleRegion(regionKo: String) {
        _filterState.value = _filterState.value.let { current ->
            if (regionKo in current.regions) {
                current.copy(regions = current.regions - regionKo)
            } else {
                current.copy(regions = current.regions + regionKo)
            }
        }
    }

    fun clearRegions() {
        _filterState.value = _filterState.value.copy(regions = emptyList())
    }

    // ── Paid local placement ───────────────────────────────────────────────────────

    private val _promotedExhibition = MutableStateFlow<PromotedExhibition?>(null)
    val promotedExhibition: StateFlow<PromotedExhibition?> = _promotedExhibition

    // The delivery service enforces one impression per installation and Seoul
    // day. Cache every attempted locality so recomposition/filter toggles never
    // consume another delivery or make an already-visible placement disappear.
    private val promotionCache = mutableMapOf<String, PromotedExhibition?>()

    // ── My List filter ────────────────────────────────────────────────────────

    private val _showMyListOnly = MutableStateFlow(false)
    val showMyListOnly: StateFlow<Boolean> = _showMyListOnly

    fun setShowMyListOnly(enabled: Boolean) {
        _showMyListOnly.value = enabled
    }

    fun clearAllFilters() {
        _filterState.value = FilterState()
        _selectedCity.value = null
    }

    // ── Bookmarks ────────────────────────────────────────────────────────────

    val bookmarkedIds: StateFlow<Set<String>> =
        bookmarkRepository.observeBookmarkedIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleBookmark(exhibitionId: String) {
        viewModelScope.launch {
            if (bookmarkRepository.isBookmarked(exhibitionId)) {
                bookmarkRepository.removeBookmark(exhibitionId)
            } else {
                bookmarkRepository.addBookmark(exhibitionId)
            }
        }
    }

    fun clearAllBookmarks() {
        viewModelScope.launch { bookmarkRepository.clearAll() }
    }

    // ── Profile sign-up nudge ─────────────────────────────────────────────

    private val _showSignUpNudge = MutableStateFlow(false)
    val showSignUpNudge: StateFlow<Boolean> = _showSignUpNudge

    // Session-only suppression: set when the user taps "Sign in" (intent, not
    // a permanent dismissal). Keeps the nudge from re-appearing for the rest of
    // this process if a combine() input changes, while still allowing it on a
    // fresh launch if they never actually signed in.
    private val _signUpNudgeSuppressed = MutableStateFlow(false)

    /** Close the sheet without persisting the one-time flag (e.g. user tapped
     *  "Sign in" — intent only; the nudge should still fire on a later launch
     *  if they bail before authenticating). */
    fun hideSignUpNudge() {
        _signUpNudgeSuppressed.value = true
        _showSignUpNudge.value = false
    }

    fun dismissSignUpNudge() {
        // Clear the local flag first so the sheet always closes immediately;
        // a slow or failing DataStore write must never strand the bottom sheet.
        // The combine() in init re-derives from the persisted flag, so a failed
        // write at worst lets the nudge reappear later (acceptable) rather than
        // leaving an undismissable sheet.
        _showSignUpNudge.value = false
        viewModelScope.launch {
            runCatching { profileNudgeRepository.setProfileNudgeShown() }
        }
    }

    // ── Filtered exhibitions ─────────────────────────────────────────────────

    val filteredExhibitions: StateFlow<ExhibitionListState> =
        combine(
            _allExhibitions, _filterState, _selectedCity, _showMyListOnly, bookmarkedIds, _searchQuery,
        ) { values ->
            val state = values[0] as ExhibitionListState
            val filter = values[1] as FilterState
            val city = values[2] as String?
            @Suppress("UNCHECKED_CAST")
            val myListOnly = values[3] as Boolean
            @Suppress("UNCHECKED_CAST")
            val bookmarked = values[4] as Set<String>
            val query = (values[5] as String).trim().lowercase()
            when (state) {
                is ExhibitionListState.Loading -> ExhibitionListState.Loading
                is ExhibitionListState.Error -> state
                is ExhibitionListState.Success -> {
                    val today = todayProvider()
                    val filtered = state.exhibitions
                        .filter { it.isVisibleInCatalog(today) }
                        .filter {
                            city == null ||
                                canonicalLocationKey(it.cityKo) == canonicalLocationKey(city)
                        }
                        .filter { filter.matches(it) }
                        .filter { !myListOnly || it.id in bookmarked }
                        .filter {
                            query.isEmpty() ||
                                it.nameKo.lowercase().contains(query) ||
                                it.nameEn.lowercase().contains(query) ||
                                it.venueNameKo.lowercase().contains(query) ||
                                it.venueNameEn.lowercase().contains(query)
                        }
                        .filter {
                            filter.selectedEventId == null || it.eventId == filter.selectedEventId
                        }
                    ExhibitionListState.Success(filtered)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExhibitionListState.Loading,
        )

    // ── Map display mode + pins ──────────────────────────────────────────────

    private val _mapDisplayMode = MutableStateFlow(MapDisplayMode.MY_LIST)
    val mapDisplayMode: StateFlow<MapDisplayMode> = _mapDisplayMode

    fun setMapDisplayMode(mode: MapDisplayMode) {
        _mapDisplayMode.value = mode
    }

    val myListMapPins: StateFlow<List<ExhibitionMapPin>> =
        combine(_allExhibitions, bookmarkedIds, language, _activeEventsById) { state, bookmarked, lang, eventsById ->
            val today = todayProvider()
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.id in bookmarked }
                ?.filter { it.isVisibleInCatalog(today) }
                ?.mapNotNull { it.toMapPin(lang, eventsById) }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allMapPins: StateFlow<List<ExhibitionMapPin>> =
        combine(_allExhibitions, language, _activeEventsById) { state, lang, eventsById ->
            val today = todayProvider()
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.isVisibleInCatalog(today) }
                ?.mapNotNull { it.toMapPin(lang, eventsById) }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Exhibition lookup ───────────────────────────────────────────────────

    fun findExhibitionById(id: String): Exhibition? =
        (_allExhibitions.value as? ExhibitionListState.Success)
            ?.exhibitions
            ?.firstOrNull { it.id == id }

    // ── Data loading ────────────────────────────────────────────────────────

    fun loadFeaturedExhibitions() {
        startFeaturedLoad(preserveCurrentContent = false)
    }

    private fun startFeaturedLoad(preserveCurrentContent: Boolean) {
        if (featuredLoadInProgress) return
        featuredLoadInProgress = true
        beginLoad()
        viewModelScope.launch {
            val hadContent = _featuredState.value is ExhibitionListState.Success
            if (!preserveCurrentContent || !hadContent) {
                _featuredState.value = ExhibitionListState.Loading
            }
            try {
                exhibitionRepository.getFeaturedExhibitions()
                    .onSuccess { exhibitions ->
                        val today = todayProvider()
                        _featuredState.value = ExhibitionListState.Success(
                            exhibitions.filter { it.isVisibleInCatalog(today) }
                        )
                    }
                    .onFailure {
                        val msg = classifyError(it)
                        println("ERROR [TabsViewModel] loadFeaturedExhibitions: ${it.message}")
                        if (!preserveCurrentContent || !hadContent) {
                            _featuredState.value = ExhibitionListState.Error(msg)
                        }
                    }
            } finally {
                featuredLoadInProgress = false
                endLoad()
            }
        }
    }

    fun loadAllExhibitions() {
        startAllExhibitionsLoad(preserveCurrentContent = false)
    }

    private fun startAllExhibitionsLoad(preserveCurrentContent: Boolean) {
        if (allExhibitionsLoadInProgress) return
        allExhibitionsLoadInProgress = true
        beginLoad()
        viewModelScope.launch {
            val hadContent = _allExhibitions.value is ExhibitionListState.Success
            if (!preserveCurrentContent || !hadContent) {
                _allExhibitions.value = ExhibitionListState.Loading
            }
            try {
                exhibitionRepository.getExhibitions()
                    .onSuccess {
                        _allExhibitions.value = ExhibitionListState.Success(it)
                        lastSuccessfulCatalogLoadAtMillis = nowMillisProvider()
                    }
                    .onFailure {
                        val msg = classifyError(it)
                        println("ERROR [TabsViewModel] loadAllExhibitions: ${it.message}")
                        if (!preserveCurrentContent || !hadContent) {
                            _allExhibitions.value = ExhibitionListState.Error(msg)
                        }
                    }
            } finally {
                allExhibitionsLoadInProgress = false
                endLoad()
            }
        }
    }

    fun refresh() {
        startFeaturedLoad(preserveCurrentContent = true)
        startAllExhibitionsLoad(preserveCurrentContent = true)
        loadActiveEvents()
    }

    /** Refresh catalogue-backed surfaces after returning to the foreground. */
    fun refreshIfStale(maxAgeMillis: Long = FOREGROUND_CATALOG_MAX_AGE_MILLIS) {
        val lastSuccess = lastSuccessfulCatalogLoadAtMillis
        if (lastSuccess != null && nowMillisProvider() - lastSuccess < maxAgeMillis) return

        startFeaturedLoad(preserveCurrentContent = true)
        startAllExhibitionsLoad(preserveCurrentContent = true)
        loadActiveEvents()
    }

    private fun beginLoad() {
        activeLoadCount += 1
        _isRefreshing.value = true
    }

    private fun endLoad() {
        activeLoadCount = (activeLoadCount - 1).coerceAtLeast(0)
        _isRefreshing.value = activeLoadCount > 0
    }

    private fun classifyError(e: Throwable): String {
        val name = e::class.simpleName ?: ""
        return if (name.contains("UnknownHost") || name.contains("Connect") || name.contains("Timeout") || name.contains("NoRoute")) {
            "network"
        } else {
            "server"
        }
    }

    init {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvents()

        // Phase 2b — when a selected active event disappears (expired, deactivated,
        // network failure on refresh), silently clear the stranded filter so the
        // List tab doesn't show an empty feed with no way to recover.
        viewModelScope.launch {
            _activeEvents.collect { events ->
                val selected = _filterState.value.selectedEventId
                val activeIds = events.map { it.id }.toSet()
                if (selected != null && selected !in activeIds) {
                    _filterState.value = _filterState.value.copy(selectedEventId = null)
                }
            }
        }

        viewModelScope.launch {
            combine(
                bookmarkedIds,
                authState,
                profileNudgeRepository.observeProfileNudgeShown(),
                _signUpNudgeSuppressed,
            ) { bookmarked, auth, nudgeShown, suppressed ->
                auth is AuthState.Anonymous &&
                    bookmarked.size >= SIGN_UP_NUDGE_THRESHOLD &&
                    !nudgeShown &&
                    !suppressed
            }
                .distinctUntilChanged()
                .collect { shouldShow ->
                    _showSignUpNudge.value = shouldShow
                }
        }


        viewModelScope.launch {
            _selectedCity
                .collect { city ->
                    if (city == null) {
                        _promotedExhibition.value = null
                        return@collect
                    }

                    if (promotionCache.containsKey(city)) {
                        _promotedExhibition.value = promotionCache[city]
                        return@collect
                    }

                    promotionRepository.getPromotedExhibition(city, "")
                        .onSuccess { placement ->
                            promotionCache[city] = placement
                            _promotedExhibition.value = placement
                        }
                        .onFailure { error ->
                            promotionCache[city] = null
                            _promotedExhibition.value = null
                            println(
                                "ERROR [TabsViewModel] promotion_load_failed: ${error.message}",
                            )
                        }
                }
        }
    }

    // ── Factory ─────────────────────────────────────────────────────────────

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
        ): ViewModelProvider.Factory = viewModelFactory {
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

private fun preferredLocationLabel(values: List<String>): String =
    values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        .orEmpty()

// Default when no repository is wired. Returns false ("not yet shown") so a
// dropped production wiring degrades to a visible (and test-detectable) nudge
// rather than silently disabling the feature with no signal.
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
