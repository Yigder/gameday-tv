package com.gameday.tv.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Which streams hold a hardware video decoder, and each stream position's decoding choice
 * ([DecoderMode]), saved for this device. Nothing switches between hardware and software on its
 * own: a stream decodes the way its position is set.
 *
 * Main thread only.
 */
object DecoderBudget {
    private const val PREFS = "gameday_device"

    private var prefs: SharedPreferences? = null
    private val holders = LinkedHashSet<Any>()

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    val hardwareInUse: Int get() = holders.size

    fun acquire(owner: Any) {
        holders += owner
    }

    fun release(owner: Any) {
        holders -= owner
    }

    /** The decoding choice for one stream position ([DecoderSlot]); Hardware unless set. */
    fun mode(slot: String): DecoderMode =
        prefs?.getString("decoder_$slot", null)?.let { name -> DecoderMode.entries.firstOrNull { it.name == name } } ?: DecoderMode.HARDWARE

    fun setMode(slot: String, mode: DecoderMode) {
        prefs?.edit()?.putString("decoder_$slot", mode.name)?.apply()
    }
}

/** How one stream decodes its video. (The old "Automatic" setting reads as Hardware.) */
enum class DecoderMode(val label: String) {
    /** The TV's video decoder: smooth and light on the CPU, but each TV has only a few. */
    HARDWARE("Hardware"),
    /** On the CPU: leaves the hardware decoders to other streams. Best for small, low-resolution screens. */
    SOFTWARE("Software");

    fun next(): DecoderMode = entries[(ordinal + 1) % entries.size]
}

/** What a playing stream is doing, for menus. */
fun decodingStatus(mode: DecoderMode): String = when (mode) {
    DecoderMode.SOFTWARE -> "Decoding on the CPU, which leaves the hardware decoders for other streams"
    DecoderMode.HARDWARE -> "Using a hardware decoder"
}

/** Stream positions that each keep their own [DecoderMode]. */
object DecoderSlot {
    const val PLAYER = "player"
    fun multiview(slot: Int) = "mv$slot"
}
