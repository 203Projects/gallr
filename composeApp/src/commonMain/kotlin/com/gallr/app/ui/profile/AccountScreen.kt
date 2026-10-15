package com.gallr.app.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gallr.app.viewmodel.SignInViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.ThoughtRepository

/**
 * The account view: sign-in while anonymous, the profile once signed in. It opens over the screen that asked for it,
 * in that screen's tab, and [onClose] returns there (spec 094 R5).
 */
@Composable
internal fun AccountScreen(
    authState: AuthState,
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    thoughtRepository: ThoughtRepository,
    tabsViewModel: TabsViewModel,
    lang: AppLanguage,
    onExhibitionTap: (Exhibition) -> Unit,
    onClose: () -> Unit,
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
    Column(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TextButton(onClick = onClose) {
            Text(text = if (lang == AppLanguage.KO) "← 뒤로" else "← BACK")
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
