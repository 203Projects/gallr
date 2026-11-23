package com.gallr.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

@Composable
internal actual fun observeBrowseFolds(): List<BrowseFold> {
    val activity = LocalContext.current.findActivity() ?: return emptyList()
    val layouts = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val layout by layouts.collectAsStateWithLifecycle(initialValue = null)
    return layout
        ?.displayFeatures
        .orEmpty()
        .filterIsInstance<FoldingFeature>()
        .filter { feature -> feature.isSeparating || feature.occlusionType == FoldingFeature.OcclusionType.FULL }
        .map { feature ->
            val bounds = feature.bounds
            BrowseFold(
                bounds = IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                vertical = feature.orientation == FoldingFeature.Orientation.VERTICAL,
            )
        }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
