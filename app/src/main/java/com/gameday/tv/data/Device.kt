package com.gameday.tv.data

import com.gameday.tv.BuildConfig

/** What the app runs on, for shared messages ("on this TV" / "on this device"). */
object Device {
    val isMobile: Boolean = BuildConfig.FLAVOR == "mobile"
    val noun: String = if (isMobile) "device" else "TV"
    /** The app's name, the same on phones and TVs. */
    const val appName: String = "GameDay TV"
}
