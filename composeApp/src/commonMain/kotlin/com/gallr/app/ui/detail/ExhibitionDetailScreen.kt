package com.gallr.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.BookmarkButton
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.exhibitionStatus
import com.gallr.shared.data.model.receptionDateLabel
import com.gallr.shared.repository.ThoughtRepository
import io.github.jan.supabase.SupabaseClient
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExhibitionDetailScreen(
    exhibition: Exhibition,
    lang: AppLanguage,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onBack: () -> Unit,
    thoughtRepository: ThoughtRepository? = null,
    authState: AuthState = AuthState.Anonymous,
    isAdmin: Boolean = false,
    supabaseClient: SupabaseClient? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    BookmarkButton(
                        isBookmarked = isBookmarked,
                        onToggle = onBookmarkToggle,
                        tintColor = MaterialTheme.colorScheme.onBackground,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { focusManager.clearFocus() }
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Cover image with placeholder ─────────────────────────────
            exhibition.coverImageUrl?.let { url ->
                if (url.isNotBlank()) {
                    AsyncImage(
                        model = url,
                        contentDescription = exhibition.localizedName(lang),
                        contentScale = ContentScale.Crop,
                        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    )
                    Spacer(Modifier.height(GallrSpacing.md))
                }
            }

            Column(modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin)) {
                // ── Exhibition name ────────────────────────────────────────
                Text(
                    text = exhibition.localizedName(lang),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Spacer(Modifier.height(GallrSpacing.sm))

                // ── Venue ──────────────────────────────────────────────────
                Text(
                    text = exhibition.localizedVenueName(lang).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                val statusLabel = exhibitionStatus(
                    exhibition.openingDate, exhibition.closingDate, today,
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
                val receptionLabel = exhibition.receptionDate?.let {
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
                    val uriHandler = LocalUriHandler.current
                    val isEmail = contact.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
                    val isPhone = !isEmail && contact.trim().matches(Regex("^[+\\d\\s()-]+$"))
                    val uri = when {
                        isEmail -> "mailto:${contact.trim()}"
                        isPhone -> "tel:${contact.trim().replace(Regex("[\\s()-]"), "")}"
                        else -> null
                    }
                    Spacer(Modifier.height(GallrSpacing.sm))
                    Text(
                        text = contact,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uri != null) GallrAccent.activeIndicator
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (uri != null) Modifier.clickable {
                            try { uriHandler.openUri(uri) } catch (_: Exception) { /* no-op */ }
                        } else Modifier,
                    )
                }

                // ── Description ────────────────────────────────────────────
                val description = exhibition.localizedDescription(lang)
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(GallrSpacing.md))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                // ── Thoughts 감상 ─────────────────────────────────────────
                if (thoughtRepository != null) {
                    Spacer(Modifier.height(GallrSpacing.md))
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(GallrSpacing.md))

                    ThoughtsSection(
                        exhibitionId = exhibition.id,
                        thoughtRepository = thoughtRepository,
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

