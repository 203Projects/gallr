package com.gallr.app.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.ThoughtRepository
import io.github.jan.supabase.SupabaseClient

@Composable
fun ProfileTab(
    authState: AuthState,
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    thoughtRepository: ThoughtRepository,
    supabaseClient: SupabaseClient,
    viewModel: TabsViewModel,
    lang: AppLanguage,
    onExhibitionTap: (com.gallr.shared.data.model.Exhibition) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (authState) {
        is AuthState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
            }
        }
        is AuthState.Anonymous -> {
            SignInScreen(
                supabaseClient = supabaseClient,
                authRepository = authRepository,
                lang = lang,
                modifier = modifier,
            )
        }
        is AuthState.Authenticated -> {
            ProfileScreen(
                user = authState.user,
                authRepository = authRepository,
                profileRepository = profileRepository,
                thoughtRepository = thoughtRepository,
                supabaseClient = supabaseClient,
                viewModel = viewModel,
                lang = lang,
                onExhibitionTap = onExhibitionTap,
                modifier = modifier,
            )
        }
    }
}
