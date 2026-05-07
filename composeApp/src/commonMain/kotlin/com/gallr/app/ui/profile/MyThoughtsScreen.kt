package com.gallr.app.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.detail.ThoughtCard
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Thought
import com.gallr.shared.repository.ThoughtRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import com.gallr.shared.data.network.dto.ThoughtDto
import com.gallr.shared.data.network.dto.ProfileDto
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyThoughtsScreen(
    thoughtRepository: ThoughtRepository,
    supabaseClient: SupabaseClient,
    lang: AppLanguage,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var thoughts by remember { mutableStateOf<List<Thought>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        try {
            val userId = supabaseClient.auth.retrieveUserForCurrentSession()?.id ?: return@LaunchedEffect
            val dtos = supabaseClient.postgrest
                .from("thoughts")
                .select {
                    filter { eq("user_id", userId) }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<ThoughtDto>()

            val profile = supabaseClient.postgrest
                .from("profiles")
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<ProfileDto>()

            thoughts = dtos.map { dto ->
                Thought(
                    id = dto.id,
                    userId = dto.userId,
                    exhibitionId = dto.exhibitionId,
                    content = dto.content,
                    isApproved = dto.isApproved,
                    createdAt = dto.createdAt,
                    updatedAt = dto.updatedAt,
                    authorDisplayName = profile?.displayName ?: "",
                    authorAvatarUrl = profile?.avatarUrl,
                )
            }
        } catch (_: Exception) {
            thoughts = emptyList()
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (lang) {
                            AppLanguage.KO -> "내 감상"
                            AppLanguage.EN -> "My Thoughts"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = GallrSpacing.screenMargin)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isLoading) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else if (thoughts.isEmpty()) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "아직 감상이 없어요."
                        AppLanguage.EN -> "No thoughts yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                thoughts.forEach { thought ->
                    ThoughtCard(
                        thought = thought,
                        lang = lang,
                        isOwn = true,
                        onDelete = {
                            scope.launch {
                                try {
                                    thoughtRepository.deleteThought(thought.id)
                                    refreshTrigger++
                                } catch (_: Exception) {}
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(GallrSpacing.lg))
        }
    }
}
