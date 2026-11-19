package com.gallr.app

/**
 * Features that are built and tested but not shown to readers in the current release. Each switch is flipped in
 * code, with the release that shows the feature, so a store build never depends on a server setting for it.
 */
object ReleaseFeatures {
    /**
     * Routes (동선): the Home rail and 동선 둘러보기, the 동선 section and count on MY, 동선에 추가 on the exhibition
     * page, the composer, the planner and public route previews. Hidden for 1.14.0 (owner decision 2026-10-20):
     * no listed route exists yet and the composer needs a UX pass. The shared web page `/route/{id}` stays, since it
     * is reached only from a share. Flip to true with the release that brings routes back (TODOS.md).
     */
    const val ROUTES: Boolean = false
}
