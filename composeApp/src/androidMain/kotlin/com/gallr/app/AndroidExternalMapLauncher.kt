package com.gallr.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gallr.shared.map.ExternalMapDestination

class AndroidExternalMapLauncher(
    context: Context,
) : ExternalMapLauncher {
    private val applicationContext = context.applicationContext

    override fun open(destination: ExternalMapDestination): Result<Unit> =
        runCatching {
            val coordinates = "${destination.latitude},${destination.longitude}"
            val query = "$coordinates(${destination.label})"
            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:$coordinates?q=${Uri.encode(query)}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            check(intent.resolveActivity(applicationContext.packageManager) != null) {
                "No map application is available"
            }
            applicationContext.startActivity(intent)
        }
}
