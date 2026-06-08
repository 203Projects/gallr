package com.gallr.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 8pt grid spacing system.
 *
 * All layout decisions should reference these tokens rather than hardcoded Dp values.
 * Base unit: 8dp. Every token is a multiple or fraction of the base unit.
 */
object GallrSpacing {
    /** 4dp — tight internal padding (icon margins, small gaps) */
    val xs = 4.dp

    /** 8dp — chip internal padding, label gap */
    val sm = 8.dp

    /** 16dp — card internal padding, screen horizontal margin */
    val md = 16.dp

    /** 24dp — card-to-card gap, section sub-spacing */
    val lg = 24.dp

    /** 32dp — major section spacing */
    val xl = 32.dp

    /** 48dp — full-screen section breaks */
    val xxl = 48.dp

    /** 8dp — column gutter width */
    val gutterWidth = 8.dp

    /** 16dp — left/right screen edge padding */
    val screenMargin = 16.dp
}

/** Fixed dimensions for the Featured event pager. */
object GallrEventCard {
    /** Seed/fallback height for the multi-event pager, used only for the first frame
     *  before FeaturedScreen measures the tallest card's natural height. The single
     *  card and the settled pager both wrap to content, so this value is rarely seen. */
    val pagerHeight = 180.dp
    /** 26dp — dot-indicator strip height (mockup --dots-height). */
    val dotsHeight = 26.dp
}
