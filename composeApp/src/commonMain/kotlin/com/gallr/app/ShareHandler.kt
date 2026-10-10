package com.gallr.app

import com.gallr.app.share.ExhibitionStoryCardPalette
import com.gallr.app.share.StoryCardImage
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

interface ShareHandler {
    fun shareApp()

    /** Render and encode once; failures propagate and missing covers use a placeholder. */
    suspend fun renderExhibitionStoryCard(
        exhibition: Exhibition,
        lang: AppLanguage,
        palette: ExhibitionStoryCardPalette,
    ): StoryCardImage

    /** Present this exact export; invoke onDismiss after the native sheet closes or no target exists. */
    fun shareStoryCard(
        card: StoryCardImage,
        onDismiss: () -> Unit,
        onPresented: () -> Unit = {},
    )
}

expect fun createShareHandler(): ShareHandler
