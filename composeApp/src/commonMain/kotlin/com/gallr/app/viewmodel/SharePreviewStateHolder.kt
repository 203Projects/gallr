package com.gallr.app.viewmodel

import com.gallr.app.share.StoryCardImage
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SharePreviewState {
    data object Rendering : SharePreviewState

    data class Ready(
        val card: StoryCardImage,
        val sharing: Boolean = false,
    ) : SharePreviewState

    data object Failed : SharePreviewState
}

/** Composition-owned orchestration; close cancels rendering and ignores late sheet callbacks. */
class SharePreviewStateHolder(
    private val scope: CoroutineScope,
    private val render: suspend () -> StoryCardImage,
    private val present: (StoryCardImage, () -> Unit) -> Unit,
) {
    private val log = AppLog.tagged("SharePreview")
    private val mutableState = MutableStateFlow<SharePreviewState>(SharePreviewState.Rendering)
    val state = mutableState.asStateFlow()
    private var renderJob: Job? = null
    private var closed = false

    init {
        renderCard()
    }

    fun retry() {
        if (!closed && mutableState.value is SharePreviewState.Failed) renderCard()
    }

    private fun renderCard() {
        mutableState.value = SharePreviewState.Rendering
        renderJob =
            scope.launch {
                try {
                    val card = render()
                    ensureActive()
                    if (!closed) mutableState.value = SharePreviewState.Ready(card)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log.warn("render_story_card", error)
                    if (!closed) mutableState.value = SharePreviewState.Failed
                }
            }
    }

    fun share() {
        val ready = mutableState.value as? SharePreviewState.Ready ?: return
        if (closed || ready.sharing) return
        mutableState.value = ready.copy(sharing = true)
        try {
            present(ready.card) {
                if (!closed) mutableState.value = ready.copy(sharing = false)
            }
        } catch (error: Exception) {
            log.warn("share_exhibition", error)
            mutableState.value = ready.copy(sharing = false)
        }
    }

    fun close() {
        closed = true
        renderJob?.cancel()
    }
}
