package com.gallr.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gallr.app.platform.rememberOpenExternalUri
import com.gallr.app.ui.components.BookmarkButton
import com.gallr.app.ui.components.ExhibitionCurationBadges
import com.gallr.app.ui.components.backButtonLabel
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.CuratorNoteUiState
import com.gallr.app.viewmodel.ExhibitionThoughtsViewModel
import com.gallr.app.viewmodel.shouldOfferVisitPrompt
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
import com.gallr.shared.observability.AppLog
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
    isVisited: Boolean = false,
    isVisitSaving: Boolean = false,
    visitSaveFailed: Boolean = false,
    onMarkVisited: () -> Unit = {},
    onBack: () -> Unit,
    thoughtRepository: ThoughtRepository? = null,
    authState: AuthState = AuthState.Anonymous,
    isAdmin: Boolean = false,
    addToRoute: AddToRouteControl? = null,
    curatorNote: CuratorNoteUiState = CuratorNoteUiState.Ready(null),
    scrollToNote: Boolean = false,
    galleryImages: List<ExhibitionImage> = emptyList(),
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
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data, shape = RectangleShape) }
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
                // ── Exhibition name ────────────────────────────────────────
                Text(
                    text = exhibition.localizedName(lang),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

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
                        shape = RectangleShape,
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
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = GallrAccent.activeIndicator,
                    )
                }

                // ── Reception date (orange label) ────────────────────────
                val receptionLabel =
                    exhibition.receptionDate?.let {
                        receptionDateLabel(it, exhibition.closingDate, lang, exhibition.openingTime)
                    }
                if (receptionLabel != null) {
                    Spacer(Modifier.height(GallrSpacing.sm))
                    Text(
                        text = receptionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = GallrAccent.activeIndicator,
                    )
                }

                if (
                    shouldOfferVisitPrompt(
                        exhibition = exhibition,
                        today = today,
                        isVisited = isVisited,
                    )
                ) {
                    Spacer(Modifier.height(GallrSpacing.md))
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text =
                                    if (lang == AppLanguage.KO) {
                                        "이 전시를 방문했나요?"
                                    } else {
                                        "DID YOU VISIT THIS EXHIBITION?"
                                    },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(GallrSpacing.xs))
                            Text(
                                text = "MY GALLR",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = onMarkVisited,
                            enabled = !isVisitSaving,
                            shape = RectangleShape,
                            contentPadding = PaddingValues(horizontal = GallrSpacing.sm),
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) {
                            if (isVisitSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            } else {
                                Text(
                                    text = if (lang == AppLanguage.KO) "기록하기" else "RECORD VISIT",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Spacer(Modifier.width(GallrSpacing.xs))
                                Text(
                                    text = "→",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = GallrAccent.interactionFeedback,
                                )
                            }
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    if (visitSaveFailed) {
                        Spacer(Modifier.height(GallrSpacing.sm))
                        Text(
                            text =
                                if (lang == AppLanguage.KO) {
                                    "! 방문을 저장하지 못했습니다. 다시 시도해 주세요."
                                } else {
                                    "! Couldn’t save this visit. Please try again."
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (uri != null) {
                                GallrAccent.activeIndicator
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
                        shape = RectangleShape,
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
                if (thoughtRepository != null) {
                    val currentUserId = (authState as? AuthState.Authenticated)?.user?.id
                    val thoughtsViewModel: ExhibitionThoughtsViewModel =
                        viewModel(
                            key = "exhibition-thoughts-${exhibition.id}-${currentUserId.orEmpty()}",
                            factory =
                                ExhibitionThoughtsViewModel.factory(
                                    exhibitionId = exhibition.id,
                                    currentUserId = currentUserId,
                                    thoughtRepository = thoughtRepository,
                                ),
                        )
                    Spacer(Modifier.height(GallrSpacing.md))
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(GallrSpacing.md))

                    ThoughtsSection(
                        viewModel = thoughtsViewModel,
                        authState = authState,
                        lang = lang,
                        isAdmin = isAdmin,
                        onSignInNeeded = { onBack() },
                    )
                }

                Spacer(Modifier.height(GallrSpacing.lg))
            }
        }
    }
}
