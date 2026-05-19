package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.ProfileNudgeRepository
import com.gallr.shared.repository.ThemeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TabsViewModelSignUpNudgeTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `anonymous user sees sign up nudge when bookmark count reaches five`() = runTest(dispatcher) {
        val bookmarks = FakeBookmarkRepository(setOf("1", "2", "3", "4"))
        val nudge = FakeProfileNudgeRepository(shown = false)
        val vm = buildViewModel(bookmarks = bookmarks, nudge = nudge)
        backgroundScope.launch { vm.showSignUpNudge.collect {} }

        bookmarks.addBookmark("5")
        advanceUntilIdle()

        assertTrue(vm.showSignUpNudge.value)
    }

    @Test
    fun `authenticated user never sees sign up nudge at threshold`() = runTest(dispatcher) {
        val bookmarks = FakeBookmarkRepository(setOf("1", "2", "3", "4"))
        val nudge = FakeProfileNudgeRepository(shown = false)
        val auth = MutableStateFlow<AuthState>(
            AuthState.Authenticated(GallrUser(id = "u1", displayName = "User", avatarUrl = null))
        )
        val vm = buildViewModel(bookmarks = bookmarks, nudge = nudge, auth = auth)
        backgroundScope.launch { vm.showSignUpNudge.collect {} }

        bookmarks.addBookmark("5")
        advanceUntilIdle()

        assertFalse(vm.showSignUpNudge.value)
    }

    @Test
    fun `dismissed sign up nudge is persisted and not shown again`() = runTest(dispatcher) {
        val bookmarks = FakeBookmarkRepository(setOf("1", "2", "3", "4", "5"))
        val nudge = FakeProfileNudgeRepository(shown = false)
        val vm = buildViewModel(bookmarks = bookmarks, nudge = nudge)
        backgroundScope.launch { vm.showSignUpNudge.collect {} }
        advanceUntilIdle()

        vm.dismissSignUpNudge()
        advanceUntilIdle()

        assertTrue(nudge.observeProfileNudgeShown().value)
        assertFalse(vm.showSignUpNudge.value)

        bookmarks.addBookmark("6")
        advanceUntilIdle()

        assertFalse(vm.showSignUpNudge.value)
    }

    @Test
    fun `nudge stays hidden when persisted flag is already shown`() = runTest(dispatcher) {
        val bookmarks = FakeBookmarkRepository(setOf("1", "2", "3", "4", "5"))
        val nudge = FakeProfileNudgeRepository(shown = true)
        val vm = buildViewModel(bookmarks = bookmarks, nudge = nudge)
        backgroundScope.launch { vm.showSignUpNudge.collect {} }

        advanceUntilIdle()

        assertFalse(vm.showSignUpNudge.value)
    }

    @Test
    fun `four bookmarks below threshold does not show nudge`() = runTest(dispatcher) {
        val bookmarks = FakeBookmarkRepository(setOf("1", "2", "3", "4"))
        val nudge = FakeProfileNudgeRepository(shown = false)
        val vm = buildViewModel(bookmarks = bookmarks, nudge = nudge)
        backgroundScope.launch { vm.showSignUpNudge.collect {} }

        advanceUntilIdle()

        assertFalse(vm.showSignUpNudge.value)
    }

    @Test
    fun `signing in while nudge is showing hides the nudge`() = runTest(dispatcher) {
        val bookmarks = FakeBookmarkRepository(setOf("1", "2", "3", "4", "5"))
        val nudge = FakeProfileNudgeRepository(shown = false)
        val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
        val vm = buildViewModel(bookmarks = bookmarks, nudge = nudge, auth = auth)
        backgroundScope.launch { vm.showSignUpNudge.collect {} }
        advanceUntilIdle()
        assertTrue(vm.showSignUpNudge.value)

        auth.value = AuthState.Authenticated(GallrUser(id = "u1", displayName = "User", avatarUrl = null))
        advanceUntilIdle()

        assertFalse(vm.showSignUpNudge.value)
    }

    @Test
    fun `hideSignUpNudge closes sheet without persisting and stays suppressed this session`() =
        runTest(dispatcher) {
            val bookmarks = FakeBookmarkRepository(setOf("1", "2", "3", "4", "5"))
            val nudge = FakeProfileNudgeRepository(shown = false)
            val vm = buildViewModel(bookmarks = bookmarks, nudge = nudge)
            backgroundScope.launch { vm.showSignUpNudge.collect {} }
            advanceUntilIdle()
            assertTrue(vm.showSignUpNudge.value)

            vm.hideSignUpNudge()
            advanceUntilIdle()

            // Sheet closed, but the one-time flag was NOT persisted (intent only).
            assertFalse(vm.showSignUpNudge.value)
            assertFalse(nudge.observeProfileNudgeShown().value)

            // A later combine input change must not re-surface it this session.
            bookmarks.addBookmark("6")
            advanceUntilIdle()
            assertFalse(vm.showSignUpNudge.value)
        }

    private fun buildViewModel(
        bookmarks: FakeBookmarkRepository = FakeBookmarkRepository(),
        nudge: FakeProfileNudgeRepository = FakeProfileNudgeRepository(),
        auth: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.Anonymous),
    ): TabsViewModel =
        TabsViewModel(
            exhibitionRepository = FakeExhibitionRepository(emptyList()),
            bookmarkRepository = bookmarks,
            languageRepository = FakeLanguageRepository(),
            themeRepository = FakeThemeRepository(),
            eventRepository = FakeEventRepository(),
            authState = auth,
            profileNudgeRepository = nudge,
        )
}

private class FakeProfileNudgeRepository(
    shown: Boolean = false,
) : ProfileNudgeRepository {
    private val shownState = MutableStateFlow(shown)

    override fun observeProfileNudgeShown(): StateFlow<Boolean> = shownState.asStateFlow()

    override suspend fun setProfileNudgeShown() {
        shownState.value = true
    }
}

private class FakeBookmarkRepository(initial: Set<String> = emptySet()) : BookmarkRepository {
    private val state = MutableStateFlow(initial)
    private var listener: (suspend () -> Unit)? = null

    override fun observeBookmarkedIds(): Flow<Set<String>> = state.asStateFlow()

    override suspend fun addBookmark(exhibitionId: String) {
        state.value = state.value + exhibitionId
        listener?.invoke()
    }

    override suspend fun removeBookmark(exhibitionId: String) {
        state.value = state.value - exhibitionId
        listener?.invoke()
    }

    override suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in state.value

    override suspend fun clearAll() {
        state.value = emptySet()
        listener?.invoke()
    }

    override fun setMutationListener(listener: suspend () -> Unit) {
        this.listener = listener
    }
}

private class FakeExhibitionRepository(
    private val exhibitions: List<Exhibition>,
) : ExhibitionRepository {
    override suspend fun getFeaturedExhibitions(): Result<List<Exhibition>> =
        Result.success(exhibitions.filter { it.isFeatured })

    override suspend fun getExhibitions(): Result<List<Exhibition>> =
        Result.success(exhibitions)
}

private class FakeLanguageRepository : LanguageRepository {
    private val language = MutableStateFlow(AppLanguage.KO)
    override fun observeLanguage(): Flow<AppLanguage> = language
    override suspend fun setLanguage(language: AppLanguage) {
        this.language.value = language
    }
}

private class FakeThemeRepository : ThemeRepository {
    private val mode = MutableStateFlow(ThemeMode.SYSTEM)
    override fun observeThemeMode(): Flow<ThemeMode> = mode
    override suspend fun setThemeMode(mode: ThemeMode) {
        this.mode.value = mode
    }
}

private class FakeEventRepository : EventRepository {
    override suspend fun getActiveEvents(): Result<List<Event>> = Result.success(emptyList())
    override suspend fun getEventById(id: String): Result<Event?> = Result.success(null)
    override suspend fun getExhibitionsForEvent(id: String): Result<List<Exhibition>> =
        Result.success(emptyList())
}
