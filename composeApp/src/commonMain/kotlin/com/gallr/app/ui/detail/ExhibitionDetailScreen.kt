package com.gallr.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gallr.app.platform.rememberOpenExternalUri
import com.gallr.app.ui.components.BookmarkButton
import com.gallr.app.ui.components.ExhibitionCurationBadges
import com.gallr.app.ui.components.ExhibitionStatusLabel
import com.gallr.app.ui.components.backButtonLabel
import com.gallr.app.ui.diary.DiaryEntryControl
import com.gallr.app.ui.diary.DiaryEntrySheet
import com.gallr.app.ui.diary.DiaryVisitRow
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.CuratorNoteUiState
import com.gallr.app.viewmodel.ExhibitionThoughtsViewModel
import com.gallr.shared.curatornote.curatorNoteFactChips
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionImage
import com.gallr.shared.data.model.curationBadges
import com.gallr.shared.data.model.exhibitionStatus
import com.gallr.shared.data.model.galleryPages
import com.gallr.shared.data.model.noteImages
import com.gallr.shared.data.model.receptionDateLabel
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import com.gallr.shared.diary.canRecordVisit
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.HiddenThoughtAuthorRepository
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.repository.ThoughtRepository
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_upload
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt
import kotlin.time.Clock

private val exhibitionDetailLog = AppLog.tagged("ExhibitionDetailScreen")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExhibitionDetailScreen(
    exhibition: Exhibition,
    lang: AppLanguage,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onShare: () -> Unit = {},
    onGalleryTap: () -> Unit = {},
    onOpenMap: (() -> Unit)? = null,
    onContactOpened: () -> Unit = {},
    onTicketOpened: () -> Unit = {},
    /** The diary row and entry sheet (spec 103 US1); null leaves both out. */
    diaryEntry: DiaryEntryControl? = null,
    onBack: () -> Unit,
    /** Opens sign-in over this screen, so the visitor comes back to the same exhibition (spec 094). */
    onSignInNeeded: () -> Unit = {},
    /** The 감상 section needs all three; without them the section is left out (spec 103 US4). */
    thoughtRepository: ThoughtRepository? = null,
    hiddenThoughtAuthorRepository: HiddenThoughtAuthorRepository? = null,
    publicNoteRepository: PublicNoteRepository? = null,
    authState: AuthState = AuthState.Anonymous,
    addToRoute: AddToRouteControl? = null,
    curatorNote: CuratorNoteUiState = CuratorNoteUiState.Ready(null),
    scrollToNote: Boolean = false,
    galleryImages: List<ExhibitionImage> = emptyList(),
    /** The 내 취향 card's reason, shown under the title when the page was opened from it (spec 101 FR-011). */
    tasteReason: String? = null,
) {
    val openExternalUri = rememberOpenExternalUri()
    val snackbarHostState = remember { SnackbarHostState() }
    addToRoute?.let { AddedToRouteSnackbar(it, snackbarHostState, lang) }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    var contentTop by remember { mutableStateOf(0f) }
    var noteOffset by remember(exhibition.id) { mutableStateOf<Int?>(null) }
    var scrolledToNote by remember(exhibition.id) { mutableStateOf(false) }
    LaunchedEffect(scrollToNote, noteOffset) {
        val target = noteOffset
        if (scrollToNote && target != null && !scrolledToNote) {
            scrolledToNote = true
            scrollState.animateScrollTo(target)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data, shape = MaterialTheme.shapes.small)
            }
        },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backButtonLabel(lang) },
                    ) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onShare,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_upload),
                            contentDescription = if (lang == AppLanguage.KO) "전시 공유" else "Share exhibition",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    BookmarkButton(
                        isBookmarked = isBookmarked,
                        onToggle = onBookmarkToggle,
                        language = lang,
                        tintColor = MaterialTheme.colorScheme.onBackground,
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                    .onGloballyPositioned { contentTop = it.positionInWindow().y }
                    .verticalScroll(scrollState),
        ) {
            // ── Cover, then the gallery when the exhibition has more pictures ──
            val galleryPages =
                remember(exhibition.coverImageUrl, galleryImages) { exhibition.galleryPages(galleryImages) }
            if (galleryPages.isNotEmpty()) {
                ExhibitionGallery(pages = galleryPages, lang = lang)
                Spacer(Modifier.height(GallrSpacing.md))
            }

            Column(modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin)) {
                // ── Exhibition name, and why 내 취향 picked it ─────────────
                // One node, so a screen reader hears the reason with the title it belongs to.
                Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        text = exhibition.localizedName(lang),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    tasteReason?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = GallrSpacing.xs),
                        )
                    }
                }

                ExhibitionCurationBadges(
                    badges = exhibition.curationBadges(),
                    language = lang,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = GallrSpacing.sm),
                )

                Spacer(Modifier.height(GallrSpacing.sm))

                // ── Venue ──────────────────────────────────────────────────
                Text(
                    text = exhibition.localizedVenueName(lang).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onGalleryTap),
                )

                Spacer(Modifier.height(GallrSpacing.xs))

                // ── City / Region ──────────────────────────────────────────
                Text(
                    text = "${exhibition.localizedCity(lang)}, ${exhibition.localizedRegion(lang)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── Address ────────────────────────────────────────────────
                val address = exhibition.localizedAddress(lang)
                if (address.isNotBlank()) {
                    Spacer(Modifier.height(GallrSpacing.xs))
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (onOpenMap != null) {
                    Spacer(Modifier.height(GallrSpacing.md))
                    OutlinedButton(
                        onClick = onOpenMap,
                        shape = MaterialTheme.shapes.small,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                    ) {
                        Text(if (lang == AppLanguage.KO) "지도에서 열기" else "OPEN IN MAPS")
                    }
                }

                addToRoute?.let { control ->
                    AddToRouteButton(control, lang)
                }

                Spacer(Modifier.height(GallrSpacing.md))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(GallrSpacing.md))

                // ── Date range (localized) ────────────────────────────────
                Text(
                    text = exhibition.localizedDateRange(lang),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                // ── Status label (Upcoming / Closing Soon) ──────────────
                val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                val statusLabel =
                    exhibitionStatus(
                        exhibition.openingDate,
                        exhibition.closingDate,
                        today,
                    ).label(lang)
                if (statusLabel != null) {
                    Spacer(Modifier.height(GallrSpacing.sm))
                    ExhibitionStatusLabel(
                        text = statusLabel,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                // ── Reception date ───────────────────────────────────────
                val receptionLabel =
                    exhibition.receptionDate?.let {
                        receptionDateLabel(it, exhibition.closingDate, lang, exhibition.openingTime)
                    }
                if (receptionLabel != null) {
                    Spacer(Modifier.height(GallrSpacing.sm))
                    Text(
                        text = receptionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                // ── Diary row: record the visit, from the opening day on (spec 103 FR-002) ──
                val diaryRow = diaryEntry?.state?.row
                if (diaryEntry != null && diaryRow != null && canRecordVisit(exhibition, today)) {
                    Spacer(Modifier.height(GallrSpacing.md))
                    DiaryVisitRow(
                        state = diaryRow,
                        failure = diaryEntry.state.failure,
                        language = lang,
                        onClick = { diaryEntry.actions.startEditing() },
                    )
                }

                // ── Hours ────────────────────────────────────────────────
                val hours = exhibition.hours
                if (!hours.isNullOrBlank()) {
                    Spacer(Modifier.height(GallrSpacing.sm))
                    Text(
                        text = hours,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── Contact (tappable mailto: or tel:) ──────────────────
                val contact = exhibition.contact
                if (!contact.isNullOrBlank()) {
                    val isEmail = contact.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
                    val isPhone = !isEmail && contact.trim().matches(Regex("^[+\\d\\s()-]+$"))
                    val uri =
                        when {
                            isEmail -> "mailto:${contact.trim()}"
                            isPhone -> "tel:${contact.trim().replace(Regex("[\\s()-]"), "")}"
                            else -> null
                        }
                    Spacer(Modifier.height(GallrSpacing.sm))
                    Text(
                        text = contact,
                        // A link reads as one by its underline; colour stays monochrome (the accent has three roles).
                        style =
                            if (uri != null) {
                                MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                        color =
                            if (uri != null) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier =
                            if (uri != null) {
                                Modifier.clickable {
                                    openExternalUri(uri) { opened ->
                                        if (opened) {
                                            onContactOpened()
                                        } else {
                                            exhibitionDetailLog.warn("open_contact_uri")
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            },
                    )
                }

                // ── Exhibition ticket link ─────────────────────────────
                val ticketUrl = exhibition.ticketUrl
                if (!ticketUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(GallrSpacing.md))
                    OutlinedButton(
                        onClick = {
                            openExternalUri(ticketUrl) { opened ->
                                if (opened) {
                                    onTicketOpened()
                                } else {
                                    exhibitionDetailLog.warn("open_ticket_uri")
                                }
                            }
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                    ) {
                        Text(if (lang == AppLanguage.KO) "예매하기" else "Tickets")
                    }
                }

                // ── Description ────────────────────────────────────────────
                val descriptionAndCredits = exhibition.localizedDescriptionAndCredits(lang)
                if (descriptionAndCredits.isNotBlank()) {
                    Spacer(Modifier.height(GallrSpacing.md))
                    Text(
                        text = descriptionAndCredits,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                // ── Curator's note (spec 090 US3) ──────────────────────────
                val approvedNote = (curatorNote as? CuratorNoteUiState.Ready)?.note
                if (approvedNote != null) {
                    Spacer(Modifier.height(GallrSpacing.md))
                    CuratorNoteBlock(
                        note = approvedNote,
                        lang = lang,
                        coverImageUrl = exhibition.coverImageUrl?.takeIf { it.isNotBlank() },
                        factChips = curatorNoteFactChips(exhibition, lang, today),
                        images = exhibition.noteImages(galleryImages),
                        modifier =
                            Modifier.onGloballyPositioned { coordinates ->
                                val top = coordinates.positionInWindow().y - contentTop
                                noteOffset = (top + scrollState.value).roundToInt()
                            },
                    )
                }

                // ── Thoughts 감상 ─────────────────────────────────────────
                if (
                    thoughtRepository != null &&
                    hiddenThoughtAuthorRepository != null &&
                    publicNoteRepository != null
                ) {
                    // One per exhibition: the account reaches it through ThoughtsSection, so a guest's report survives
                    // sign-in and opens its sheet afterwards.
                    val thoughtsViewModel: ExhibitionThoughtsViewModel =
                        viewModel(
                            key = "exhibition-thoughts-${exhibition.id}",
                            factory =
                                ExhibitionThoughtsViewModel.factory(
                                    exhibitionId = exhibition.id,
                                    authState = authState,
                                    thoughtRepository = thoughtRepository,
                                    hiddenThoughtAuthorRepository = hiddenThoughtAuthorRepository,
                                    publicNoteRepository = publicNoteRepository,
                                ),
                        )
                    Spacer(Modifier.height(GallrSpacing.md))
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(GallrSpacing.md))

                    ThoughtsSection(
                        viewModel = thoughtsViewModel,
                        authState = authState,
                        lang = lang,
                        snackbarHostState = snackbarHostState,
                        onSignInNeeded = onSignInNeeded,
                        onEditOwnNote = { diaryEntry?.actions?.startEditing(focusNote = true) },
                        ownNote = diaryEntry?.state?.publicNote,
                        ownNoteStatus = diaryEntry?.state?.noteStatus,
                        // A 감상 is written only on a show that has opened, through the entry sheet (FR-011).
                        onWriteNote =
                            diaryEntry
                                ?.takeIf { canRecordVisit(exhibition, today) }
                                ?.let { control -> { control.actions.startEditing(focusNote = true) } },
                    )
                }

                Spacer(Modifier.height(GallrSpacing.lg))
            }
        }
    }

    // The sheet is open while the ViewModel holds a draft, so it survives sign-in over the page and a rotation.
    diaryEntry?.state?.editor?.let { editor ->
        DiaryEntrySheet(
            title = exhibition.localizedName(lang),
            venue = exhibition.localizedVenueName(lang),
            editor = editor,
            language = lang,
            actions = diaryEntry.actions,
            onSignIn = onSignInNeeded,
        )
    }
}
