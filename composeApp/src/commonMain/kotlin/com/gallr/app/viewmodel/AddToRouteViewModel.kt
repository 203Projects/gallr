package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.PersonalRouteDraftRepository
import com.gallr.shared.route.AppendResult
import com.gallr.shared.route.MAX_ROUTE_STOPS
import com.gallr.shared.route.PersonalRouteDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** What the detail page's route button offers for one exhibition (DR-D16, RO5). */
enum class AddToRouteAvailability {
    /** Ended shows and shows without a map location have no button. */
    HIDDEN,
    CAN_ADD,

    /** Already a stop; the button opens the composer. */
    IN_ROUTE,

    /** The draft holds the maximum number of stops; the button is disabled. */
    FULL,
}

/** Confirmation after an add: the draft now holds [stopCount] stops. */
data class AddedToRoute(
    val stopCount: Int,
)

data class AddToRouteUiState(
    val availability: AddToRouteAvailability,
    val message: AddedToRoute? = null,
)

/**
 * The exhibition detail page's "동선에 추가" control (spec 089 US1). It observes the device's route draft and adds
 * through [PersonalRouteDraftRepository], so the composer sees the new stop at once (RR3).
 */
class AddToRouteViewModel(
    private val exhibition: Exhibition,
    private val draftRepository: PersonalRouteDraftRepository,
    private val clock: Clock = Clock.System,
    private val analytics: RouteAnalytics = RouteAnalytics.None,
) : ViewModel() {
    private val message = MutableStateFlow<AddedToRoute?>(null)

    val state: StateFlow<AddToRouteUiState> =
        combine(draftRepository.draft, message) { draft, shown ->
            AddToRouteUiState(availability = availabilityIn(draft), message = shown)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = AddToRouteUiState(AddToRouteAvailability.HIDDEN),
        )

    fun add() {
        viewModelScope.launch {
            val result = draftRepository.append(exhibition)
            if (result is AppendResult.Added) {
                message.value = AddedToRoute(result.stopCount)
                if (result.stopCount == 1) analytics.draftStarted()
            }
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    private fun availabilityIn(draft: PersonalRouteDraft): AddToRouteAvailability {
        val ended = exhibition.closingDate < clock.todayIn(ROUTE_TIME_ZONE)
        val unlocated = exhibition.latitude == null || exhibition.longitude == null
        val stops = draft.route.stops
        return when {
            ended || unlocated -> AddToRouteAvailability.HIDDEN
            stops.any { it.exhibitionId == exhibition.id } -> AddToRouteAvailability.IN_ROUTE
            stops.size >= MAX_ROUTE_STOPS -> AddToRouteAvailability.FULL
            else -> AddToRouteAvailability.CAN_ADD
        }
    }

    companion object {
        fun factory(
            exhibition: Exhibition,
            draftRepository: PersonalRouteDraftRepository,
            analytics: RouteAnalytics = RouteAnalytics.None,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AddToRouteViewModel(
                        exhibition = exhibition,
                        draftRepository = draftRepository,
                        analytics = analytics,
                    )
                }
            }
    }
}

private const val STOP_TIMEOUT_MILLIS = 5_000L
