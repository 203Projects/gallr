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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.detail.ThoughtCard
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.MyThoughtsViewModel
import com.gallr.shared.data.model.AppLanguage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyThoughtsScreen(
    viewModel: MyThoughtsViewModel,
    lang: AppLanguage,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val retryLabel =
        when (lang) {
            AppLanguage.KO -> "다시 시도"
            AppLanguage.EN -> "Retry"
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text =
                            when (lang) {
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
                    .padding(horizontal = GallrSpacing.screenMargin)
                    .verticalScroll(rememberScrollState()),
        ) {
            if (uiState.isLoading && !uiState.hasLoaded) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                Spacer(Modifier.height(32.dp))
                if (uiState.loadFailed) {
                    GallrErrorMessage(
                        message =
                            when (lang) {
                                AppLanguage.KO -> "감상을 불러오지 못했습니다."
                                AppLanguage.EN -> "Couldn’t load your thoughts."
                            },
                        actionLabel = retryLabel,
                        onAction = viewModel::refresh,
                    )
                    Spacer(Modifier.height(GallrSpacing.md))
                }
                if (uiState.mutationFailed) {
                    GallrErrorMessage(
                        message =
                            when (lang) {
                                AppLanguage.KO -> "감상을 삭제하지 못했습니다. 다시 시도해주세요."
                                AppLanguage.EN -> "Couldn’t delete the thought. Please try again."
                            },
                    )
                    Spacer(Modifier.height(GallrSpacing.md))
                }
                if (uiState.hasLoaded && uiState.thoughts.isEmpty()) {
                    Text(
                        text =
                            when (lang) {
                                AppLanguage.KO -> "아직 감상이 없어요."
                                AppLanguage.EN -> "No thoughts yet."
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                uiState.thoughts.forEach { thought ->
                    ThoughtCard(
                        thought = thought,
                        lang = lang,
                        isOwn = true,
                        onDelete = { viewModel.deleteThought(thought.id) },
                    )
                }
            }
            Spacer(Modifier.height(GallrSpacing.lg))
        }
    }
}
