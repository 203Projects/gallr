package com.gallr.app.ui.route.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.gallr.app.ShareHandler
import com.gallr.app.share.RouteShareCardContent
import com.gallr.app.share.RouteShareCardPalette
import com.gallr.app.viewmodel.RouteSharePayload
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.map.RoutedLegEstimator
import com.gallr.shared.map.WalkingLegs
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.CancellationException

private val routeShareLog = AppLog.tagged("RouteShare")

/**
 * Renders the route card for [payload] and opens the native share sheet with the card and its link (DR-D11).
 * [onConsumed] runs first so the sheet opens once per share. [walkingLegs] gives the card the same walking distance
 * the composer shows (spec 093 FR-005).
 */
@Composable
internal fun RouteShareEffect(
    payload: RouteSharePayload?,
    language: AppLanguage,
    shareHandler: ShareHandler,
    darkCard: Boolean,
    onConsumed: () -> Unit,
    walkingLegs: WalkingLegs = WalkingLegs.EMPTY,
) {
    LaunchedEffect(payload) {
        val target = payload ?: return@LaunchedEffect
        onConsumed()
        val content =
            RouteShareCardContent.from(target.route, target.link, language, RoutedLegEstimator(walkingLegs))
        val palette = if (darkCard) RouteShareCardPalette.DARK else RouteShareCardPalette.LIGHT
        try {
            val card = shareHandler.renderRouteCard(content, palette)
            shareHandler.shareStoryCard(card, onDismiss = {})
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            routeShareLog.warn("route_card_share", error)
        }
    }
}
