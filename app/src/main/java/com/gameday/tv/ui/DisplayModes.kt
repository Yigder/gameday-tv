package com.gameday.tv.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display

/**
 * Smooth motion: asks the TV for its fastest refresh rate (e.g. 120 Hz) at the current
 * resolution, so scrolling and focus animations run at more frames per second. Saved per device.
 */
object DisplayModes {
    private const val PREFS = "gameday_device"
    private const val KEY = "smooth_motion"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(activity: Activity, on: Boolean) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        apply(activity)
    }

    /** The current mode and the fastest one at the same resolution (null when there's no faster one). */
    fun fastest(activity: Activity): Pair<Display.Mode, Display.Mode?>? {
        val display = display(activity) ?: return null
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
        return current to best?.takeIf { it.refreshRate > current.refreshRate + 1f }
    }

    /** Fastest refresh rate this TV offers at its current resolution. */
    fun maxRefresh(activity: Activity): Float {
        val display = display(activity) ?: return 60f
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: current.refreshRate
    }

    /** What this TV can do, for Settings. */
    fun describe(activity: Activity): String {
        val display = display(activity) ?: return "Unknown display"
        val mode = display.mode
        val max = maxRefresh(activity)
        val res = "${mode.physicalWidth}×${mode.physicalHeight}"
        return if (max > 61f) "This TV goes up to ${max.toInt()} Hz at $res (now ${display.refreshRate.toInt()} Hz)."
        else "This TV only offers ${max.toInt()} Hz at $res, so there's no faster mode to use."
    }

    fun apply(activity: Activity) {
        val display = display(activity) ?: return
        val attrs = activity.window.attributes
        if (enabled(activity)) {
            val current = display.mode
            val best = display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            attrs.preferredDisplayModeId = best.modeId
            attrs.preferredRefreshRate = best.refreshRate
        } else {
            attrs.preferredDisplayModeId = 0
            attrs.preferredRefreshRate = 0f
        }
        activity.window.attributes = attrs
    }

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
}
