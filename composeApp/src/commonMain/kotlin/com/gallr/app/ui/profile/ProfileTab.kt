package com.gallr.app.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gallr.app.MyTabRequest
import com.gallr.app.PlatformBackHandler
import com.gallr.app.ui.mygallr.AddGalleriesScreen
import com.gallr.app.ui.mygallr.AddPastVisitsScreen
import com.gallr.app.ui.mygallr.MyGallrScreen
import com.gallr.app.viewmodel.MyGallrMode
import com.gallr.app.viewmodel.MyGallrSection
import com.gallr.app.viewmodel.MyGallrViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.app.viewmodel.exhibitionForVisit
import com.gallr.app.viewmodel.shouldShowAccountNudge
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.MyGallrAccountNudgeRepository
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.repository.VisitRepository

@Composable
fun ProfileTab(
    authState: AuthState,
    visitRepository: VisitRepository,
    followedGalleryRepository: FollowedGalleryRepository,
    accountNudgeRepository: MyGallrAccountNudgeRepository,
    myGallrSyncStatus: MyGallrSyncStatus,
    onRetryMyGallrSync: () -> Unit,
    tabsViewModel: TabsViewModel,
    lang: AppLanguage,
    onExhibitionTap: (com.gallr.shared.data.model.Exhibition) -> Unit = {},
    onGalleryTap: (com.gallr.shared.data.model.Exhibition) -> Unit = {},
    myTabRequest: MyTabRequest? = null,
    onMyTabRequestHandled: () -> Unit = {},
    onAccount: () -> Unit = {},
    routeCount: Int = 0,
    routes: @Composable (Modifier) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val myGallrViewModel: MyGallrViewModel =
        viewModel(
            key = "my-gallr",
            factory =
                MyGallrViewModel.factory(
                    visitRepository = visitRepository,
                    followedGalleryRepository = followedGalleryRepository,
                    accountNudgeRepository = accountNudgeRepository,
                    exhibitionsState = tabsViewModel.allExhibitions,
                    language = tabsViewModel.language,
                    galleryProfileImages = tabsViewModel.galleryProfileImages,
                ),
        )
    val myGallrState by myGallrViewModel.uiState.collectAsState()
    // Each request is handled once and then cleared, so coming back to the tab later shows it as it was left.
    LaunchedEffect(myTabRequest) {
        when (myTabRequest ?: return@LaunchedEffect) {
            MyTabRequest.ADD_PAST_VISITS -> {
                myGallrViewModel.startAddingVisits()
            }

            MyTabRequest.MY_ROUTES -> {
                myGallrViewModel.selectSection(MyGallrSection.ROUTES)
            }
        }
        onMyTabRequestHandled()
    }

    when (myGallrState.mode) {
        MyGallrMode.ARCHIVE -> {
            MyGallrScreen(
                state = myGallrState,
                onAddVisits = myGallrViewModel::startAddingVisits,
                onAddGalleries = myGallrViewModel::startAddingGalleries,
                onOpenVisit = { visit ->
                    myGallrState.exhibitionForVisit(visit)?.let(onExhibitionTap)
                },
                onRemoveVisit = myGallrViewModel::removeVisit,
                onUnfollowGallery = myGallrViewModel::unfollowGallery,
                onOpenGallery = { followed ->
                    myGallrViewModel.acknowledgeGallery(followed.record.galleryKey)
                    followed.latestRelevantExhibition?.let(onGalleryTap)
                },
                onSelectSection = myGallrViewModel::selectSection,
                showAccountNudge = myGallrState.shouldShowAccountNudge(authState),
                isAuthenticated = authState is AuthState.Authenticated,
                syncStatus = myGallrSyncStatus,
                onRetrySync = onRetryMyGallrSync,
                onDismissAccountNudge = myGallrViewModel::dismissAccountNudge,
                onAccount = onAccount,
                routeCount = routeCount,
                routes = routes,
                modifier = modifier,
            )
        }

        MyGallrMode.ADD_VISITS -> {
            PlatformBackHandler(onBack = myGallrViewModel::cancelAddingVisits)
            AddPastVisitsScreen(
                state = myGallrState,
                onBack = myGallrViewModel::cancelAddingVisits,
                onSearchQueryChange = myGallrViewModel::setSearchQuery,
                onToggleSelection = myGallrViewModel::toggleSelection,
                onSave = myGallrViewModel::saveSelected,
                modifier = modifier,
            )
        }

        MyGallrMode.ADD_GALLERIES -> {
            PlatformBackHandler(onBack = myGallrViewModel::cancelAddingGalleries)
            AddGalleriesScreen(
                state = myGallrState,
                onBack = myGallrViewModel::cancelAddingGalleries,
                onSearchQueryChange = myGallrViewModel::setGallerySearchQuery,
                onToggleSelection = myGallrViewModel::toggleGallerySelection,
                onSave = myGallrViewModel::saveSelectedGalleries,
                modifier = modifier,
            )
        }
    }
}
