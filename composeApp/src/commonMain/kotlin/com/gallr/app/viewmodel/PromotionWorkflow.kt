package com.gallr.app.viewmodel

import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.PromotionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class PromotionWorkflow(
    scope: CoroutineScope,
    selectedCity: StateFlow<String?>,
    private val promotionRepository: PromotionRepository,
) {
    private val log = AppLog.tagged("PromotionWorkflow")
    private val cache = mutableMapOf<String, PromotedExhibition?>()

    private val _promotedExhibition = MutableStateFlow<PromotedExhibition?>(null)
    val promotedExhibition: StateFlow<PromotedExhibition?> = _promotedExhibition

    init {
        scope.launch {
            selectedCity.collect { city ->
                if (city == null) {
                    _promotedExhibition.value = null
                    return@collect
                }
                if (cache.containsKey(city)) {
                    _promotedExhibition.value = cache[city]
                    return@collect
                }
                promotionRepository
                    .getPromotedExhibition(city, "")
                    .onSuccess { placement ->
                        cache[city] = placement
                        _promotedExhibition.value = placement
                    }.onFailure { error ->
                        cache[city] = null
                        _promotedExhibition.value = null
                        log.error("load_promoted_exhibition", error)
                    }
            }
        }
    }
}
