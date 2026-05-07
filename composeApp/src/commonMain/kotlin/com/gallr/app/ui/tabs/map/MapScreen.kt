package com.gallr.app.ui.tabs.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.EventMapFab
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.data.model.MapDisplayMode
import com.gallr.shared.data.model.exhibitionStatus
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapMode by viewModel.mapDisplayMode.collectAsState()
    val myListPins by viewModel.myListMapPins.collectAsState()
    val allPins by viewModel.allMapPins.collectAsState()
    val lang by viewModel.language.collectAsState()
    val activeEvent by viewModel.activeEvent.collectAsState()

    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val activePins = if (mapMode == MapDisplayMode.MY_LIST) myListPins else allPins
    val locations = remember(activePins) { groupPinsByLocation(activePins) }

    // Location permission
    val locationPermission = rememberLocationPermissionState()
    LaunchedEffect(Unit) {
        if (!locationPermission.isGranted) locationPermission.request()
    }

    // Single exhibition dialog
    var selectedPin by remember { mutableStateOf<ExhibitionMapPin?>(null) }
    // Multi-exhibition bottom sheet
    var selectedLocation by remember { mutableStateOf<MapLocation?>(null) }

    val selectedTabIndex = if (mapMode == MapDisplayMode.MY_LIST) 0 else 1

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                indicator = { tabPositions ->
                    if (selectedTabIndex < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                divider = {},
            ) {
                Tab(
                    selected = mapMode == MapDisplayMode.MY_LIST,
                    onClick = { viewModel.setMapDisplayMode(MapDisplayMode.MY_LIST) },
                    text = {
                        Text(
                            text = if (lang == AppLanguage.KO) "내 전시" else "MY LIST",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
                Tab(
                    selected = mapMode == MapDisplayMode.ALL,
                    onClick = { viewModel.setMapDisplayMode(MapDisplayMode.ALL) },
                    text = {
                        Text(
                            text = if (lang == AppLanguage.KO) "전체" else "ALL",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            if (mapMode == MapDisplayMode.MY_LIST && myListPins.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(GallrSpacing.screenMargin)) {
                    Text(
                        text = if (lang == AppLanguage.KO) "저장한 전시가 없습니다.\n전시를 북마크하면 지도에 표시됩니다."
                               else "No saved exhibitions yet.\nBookmark exhibitions to see them on the map.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val initialCenter = rememberLastKnownCoordinates(
                enabled = locationPermission.isGranted,
            )
            val mapReady = rememberMapReadiness(
                permissionGranted = locationPermission.isGranted,
                coordsResolved = initialCenter != null,
            )

            if (mapReady) {
                MapView(
                    locations = locations,
                    onLocationTap = { location ->
                        if (location.count == 1) {
                            selectedPin = location.pins.first()
                        } else {
                            selectedLocation = location
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enableUserLocation = locationPermission.isGranted,
                    initialCenter = initialCenter,
                )
            } else {
                // Placeholder matches map background — invisible during the brief
                // (≤300ms) window while the cached fix resolves.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background),
                )
            }
        }
        activeEvent?.let { event ->
            EventMapFab(
                event = event,
                lang = lang,
                onTap = { onEventTap(event.id) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }

    // ── Single exhibition dialog ────────────────────────────────────────────
    selectedPin?.let { pin ->
        AlertDialog(
            onDismissRequest = { selectedPin = null },
            title = {
                Text(
                    text = pin.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            },
            text = {
                Column {
                    Text(
                        text = pin.venueName.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(GallrSpacing.xs))
                    Text(
                        text = pin.localizedDateRange(lang),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    pinStatusText(pin.openingDate, pin.closingDate, today, lang)?.let { statusLabel ->
                        Spacer(Modifier.height(GallrSpacing.xs))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = GallrAccent.activeIndicator,
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            shape = RectangleShape,
            confirmButton = {
                TextButton(onClick = {
                    val exhibition = viewModel.findExhibitionById(pin.id)
                    selectedPin = null
                    exhibition?.let { onExhibitionTap(it) }
                }) {
                    Text(
                        text = if (lang == AppLanguage.KO) "상세보기" else "VIEW DETAILS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPin = null }) {
                    Text(
                        text = if (lang == AppLanguage.KO) "닫기" else "CLOSE",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }

    // ── Multi-exhibition bottom sheet ────────────────────────────────────────
    selectedLocation?.let { location ->
        ModalBottomSheet(
            onDismissRequest = { selectedLocation = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
            shape = RectangleShape,
        ) {
            Column(modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin)) {
                Text(
                    text = location.pins.first().venueName.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(GallrSpacing.xs))
                Text(
                    text = if (lang == AppLanguage.KO) "${location.count}개의 전시" else "${location.count} Exhibitions",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(GallrSpacing.md))

                LazyColumn {
                    items(location.pins, key = { it.id }) { pin ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val exhibition = viewModel.findExhibitionById(pin.id)
                                    selectedLocation = null
                                    exhibition?.let { onExhibitionTap(it) }
                                }
                                .padding(vertical = GallrSpacing.sm),
                        ) {
                            Text(
                                text = pin.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(GallrSpacing.xs))
                            Text(
                                text = pin.localizedDateRange(lang),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            pinStatusText(pin.openingDate, pin.closingDate, today, lang)?.let { statusLabel ->
                                Spacer(Modifier.height(GallrSpacing.xs))
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = GallrAccent.activeIndicator,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                Spacer(Modifier.height(GallrSpacing.lg))
            }
        }
    }
}

private fun pinStatusText(
    openingDate: LocalDate,
    closingDate: LocalDate,
    today: LocalDate,
    lang: AppLanguage,
): String? {
    return exhibitionStatus(openingDate, closingDate, today).label(lang)
}

