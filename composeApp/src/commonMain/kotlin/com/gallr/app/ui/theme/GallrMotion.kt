package com.gallr.app.ui.theme

/**
 * Animation timing constants (DESIGN.md, Motion). State feedback is a colour or opacity shift; motion is functional,
 * never decorative, and every animation that moves or scales something stops under reduced motion.
 */
object GallrMotion {
    /** Maximum duration (ms) for press/active state color shift (FR spec: < 100ms). */
    const val PRESS_DURATION_MS: Int = 100
}
