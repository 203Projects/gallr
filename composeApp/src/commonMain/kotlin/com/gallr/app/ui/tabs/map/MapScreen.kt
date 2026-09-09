package com.gallr.app.ui.tabs.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.viewmodel.PersonalMapViewModel
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint

@Composable
fun MapScreen(
    mapViewModel: PersonalMapViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onBuildRoute: (GeoPoint) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by mapViewModel.uiState.collectAsState()
    val locationPermission = rememberLocationPermissionState()
    LaunchedEffect(Unit) {
        if (!locationPermission.isGranted) locationPermission.request()
    }
    var locationRequestKey by remember { mutableIntStateOf(0) }
    val initialCenter =
        rememberLastKnownCoordinates(
            enabled = locationPermission.isGranted,
            requestKey = locationRequestKey,
        )
    val mapReady =
        rememberMapReadiness(
            permissionGranted = locationPermission.isGranted,
            coordsResolved = initialCenter != null,
        )

    Column(modifier = modifier.fillMaxSize()) {
        MapModeTabs(
            mode = state.mode,
            lang = state.language,
            onModeChange = mapViewModel::setMode,
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        if (mapReady) {
            SeoulExhibitionMap(
                exhibitions = state.resultExhibitions,
                savedExhibitionIds = state.savedExhibitionIds,
                language = state.language,
                initialCenter = initialCenter,
                onLocationRequest = {
                    if (locationPermission.isGranted) {
                        locationRequestKey += 1
                    } else {
                        locationPermission.request()
                    }
                },
                onExhibitionTap = onExhibitionTap,
                onBuildRoute = onBuildRoute,
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background),
            )
        }
    }
}
