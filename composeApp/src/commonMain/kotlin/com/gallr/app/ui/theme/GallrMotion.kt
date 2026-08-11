package com.gallr.app.ui.theme

/**
 * Animation timing constants for the Reductionist design system.
 * State feedback relies on immediate color/opacity shift — no motion or positional animation.
 */
object GallrMotion {
    /** Maximum duration (ms) for press/active state color shift (FR spec: < 100ms). */
    const val PRESS_DURATION_MS: Int = 100
}
