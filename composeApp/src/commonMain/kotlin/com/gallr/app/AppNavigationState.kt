package com.gallr.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gallr.shared.data.model.Exhibition

internal sealed interface AppDestination {
    data object Tabs : AppDestination

    data class ExhibitionDetail(
        val exhibition: Exhibition,
    ) : AppDestination

    data class GalleryDetail(
        val exhibition: Exhibition,
    ) : AppDestination

    data class EventDetail(
        val eventId: String,
    ) : AppDestination

    data object EditorSelector : AppDestination

    data object Settings : AppDestination

    data class EditorDetail(
        val editorId: String,
    ) : AppDestination
}

@Stable
internal class AppNavigationState {
    private var galleryBackDestination: AppDestination = AppDestination.Tabs

    var selectedTab by mutableIntStateOf(0)
        private set

    var destination by mutableStateOf<AppDestination>(AppDestination.Tabs)
        private set

    var addPastVisitsRequest by mutableIntStateOf(0)
        private set

    fun selectTab(index: Int) {
        selectedTab = index
        destination = AppDestination.Tabs
    }

    fun showExhibition(exhibition: Exhibition) {
        destination = AppDestination.ExhibitionDetail(exhibition)
    }

    fun showGallery(exhibition: Exhibition) {
        galleryBackDestination = destination
        destination = AppDestination.GalleryDetail(exhibition)
    }

    fun returnFromGallery() {
        destination = galleryBackDestination
    }

    fun showEvent(eventId: String) {
        destination = AppDestination.EventDetail(eventId)
    }

    fun showEditorSelector() {
        destination = AppDestination.EditorSelector
    }

    fun showSettings() {
        destination = AppDestination.Settings
    }

    fun showEditor(editorId: String) {
        destination = AppDestination.EditorDetail(editorId)
    }

    fun showTabs() {
        destination = AppDestination.Tabs
    }

    fun showAddPastVisits() {
        addPastVisitsRequest += 1
        selectTab(3)
    }
}

@Composable
internal fun rememberAppNavigationState(): AppNavigationState = remember { AppNavigationState() }
