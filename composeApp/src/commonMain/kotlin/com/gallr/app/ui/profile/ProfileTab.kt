package com.gallr.app.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gallr.app.PlatformBackHandler
import com.gallr.app.ui.mygallr.AddGalleriesScreen
import com.gallr.app.ui.mygallr.AddPastVisitsScreen
import com.gallr.app.ui.mygallr.MyGallrScreen
import com.gallr.app.viewmodel.MyGallrMode
import com.gallr.app.viewmodel.MyGallrViewModel
import com.gallr.app.viewmodel.SignInViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.app.viewmodel.shouldShowAccountNudge
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.MyGallrAccountNudgeRepository
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.ThoughtRepository
import com.gallr.shared.repository.VisitRepository

@Composable
fun ProfileTab(
    authState: AuthState,
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    thoughtRepository: ThoughtRepository,
    visitRepository: VisitRepository,
    followedGalleryRepository: FollowedGalleryRepository,
    accountNudgeRepository: MyGallrAccountNudgeRepository,
    myGallrSyncStatus: MyGallrSyncStatus,
    onRetryMyGallrSync: () -> Unit,
    tabsViewModel: TabsViewModel,
    lang: AppLanguage,
    onExhibitionTap: (com.gallr.shared.data.model.Exhibition) -> Unit = {},
    onGalleryTap: (com.gallr.shared.data.model.Exhibition) -> Unit = {},
    addPastVisitsRequest: Int = 0,
    modifier: Modifier = Modifier,
) {
    var showAccount by remember { mutableStateOf(false) }
    val signInViewModel: SignInViewModel =
        viewModel(
            key = "sign-in",
            factory = SignInViewModel.factory(authRepository),
        )
    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) {
            signInViewModel.clearSensitiveState()
        }
    }

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
                ),
        )
    val myGallrState by myGallrViewModel.uiState.collectAsState()
    LaunchedEffect(addPastVisitsRequest) {
        if (addPastVisitsRequest > 0) {
            showAccount = false
            myGallrViewModel.startAddingVisits()
        }
    }

    if (!showAccount) {
        when (myGallrState.mode) {
            MyGallrMode.ARCHIVE -> {
                MyGallrScreen(
                    state = myGallrState,
                    onAddVisits = myGallrViewModel::startAddingVisits,
                    onAddGalleries = myGallrViewModel::startAddingGalleries,
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
                    onAccount = { showAccount = true },
                    modifier = modifier,
                )
            }

            MyGallrMode.ADD_VISITS -> {
                PlatformBackHandler(myGallrViewModel::cancelAddingVisits)
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
                PlatformBackHandler(myGallrViewModel::cancelAddingGalleries)
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
        return
    }

    PlatformBackHandler { showAccount = false }
    Column(modifier = modifier.fillMaxSize()) {
        TextButton(onClick = { showAccount = false }) {
            Text(
                text =
                    when (lang) {
                        AppLanguage.KO -> "← MY GALLR"
                        AppLanguage.EN -> "← MY GALLR"
                    },
            )
        }

        when (authState) {
            is AuthState.Loading -> {
                Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
                }
            }

            is AuthState.Anonymous -> {
                SignInScreen(
                    viewModel = signInViewModel,
                    lang = lang,
                    modifier = Modifier.weight(1f),
                )
            }

            is AuthState.Authenticated -> {
                ProfileScreen(
                    user = authState.user,
                    profileRepository = profileRepository,
                    thoughtRepository = thoughtRepository,
                    tabsViewModel = tabsViewModel,
                    lang = lang,
                    onExhibitionTap = onExhibitionTap,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
