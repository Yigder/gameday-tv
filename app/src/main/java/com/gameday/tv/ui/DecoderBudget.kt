package com.gameday.tv.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * How many videos this device can decode in hardware at once.
 *
 * TV chips often advertise far more decoder instances than they can really run (a MediaTek TV here
 * claims 16 but fails at 3): when another stream starts, Android reclaims the oldest stream's
 * decoder and that screen dies. So the limit is learned: the first time a decoder is lost while
 * several streams are running, the count of streams minus one is remembered for this device, and
 * later streams beyond it decode in software instead of fighting over the hardware.
 *
 * Main thread only.
 */
object DecoderBudget {
    private const val PREFS = "gameday_device"
    private const val KEY_LIMIT = "hw_video_decoders"

    private var prefs: SharedPreferences? = null
    private val holders = LinkedHashSet<Any>()

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Learned limit, or 0 when no limit has been observed on this device. */
    val limit: Int get() = prefs?.getInt(KEY_LIMIT, 0) ?: 0

    val hardwareInUse: Int get() = holders.size

    fun canUseHardware(): Boolean = limit == 0 || holders.size < limit

    fun acquire(owner: Any) {
        holders += owner
    }

    fun release(owner: Any) {
        holders -= owner
    }

    /**
     * [owner]'s hardware decoder failed while it was one of several streams: remember that this
     * device can't run that many. A failure with only one stream is a bad stream, not a limit.
     */
    fun learnFromFailure(owner: Any) {
        val running = holders.size
        holders -= owner
        if (running < 2) return
        val newLimit = running - 1
        if (limit == 0 || newLimit < limit) prefs?.edit()?.putInt(KEY_LIMIT, newLimit)?.apply()
    }

    fun reset() {
        prefs?.edit()?.remove(KEY_LIMIT)?.apply()
    }
}
