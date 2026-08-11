package com.gallr.app.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gallr.app.viewmodel.SignInViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.ThoughtRepository

@Composable
fun ProfileTab(
    authState: AuthState,
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    thoughtRepository: ThoughtRepository,
    tabsViewModel: TabsViewModel,
    lang: AppLanguage,
    onExhibitionTap: (com.gallr.shared.data.model.Exhibition) -> Unit = {},
    modifier: Modifier = Modifier,
) {
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

    when (authState) {
        is AuthState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
            }
        }

        is AuthState.Anonymous -> {
            SignInScreen(
                viewModel = signInViewModel,
                lang = lang,
                modifier = modifier,
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
                modifier = modifier,
            )
        }
    }
}
