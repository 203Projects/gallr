package com.gallr.app.ui.tabs.map

/** Whether the app may read the device location, and whether the system will still ask (spec 089 RO6). */
enum class LocationPermissionStatus {
    GRANTED,

    /** Not granted, and asking will show the system prompt. */
    CAN_ASK,

    /** Not granted, and the system will not show the prompt again until the user changes it in Settings. */
    DENIED_PERMANENTLY,
}
