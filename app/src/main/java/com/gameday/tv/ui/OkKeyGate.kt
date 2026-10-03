package com.gameday.tv.ui

import android.view.KeyEvent

/**
 * Holding OK triggers its action while the button is still down. If that action opens new UI
 * (a menu, a different screen), the eventual *release* of OK would land on whatever just got
 * focus and click it — e.g. "Change channel" in a freshly opened menu. Long-press handlers call
 * [swallowRelease]; [MainActivity] then drops that one release.
 */
object OkKeyGate {
    @Volatile
    private var swallowNextUp = false

    fun swallowRelease() {
        swallowNextUp = true
    }

    /** Returns true if the event should be consumed. Call for every key event the activity dispatches. */
    fun shouldConsume(event: KeyEvent): Boolean = shouldConsume(event.keyCode, event.action, event.repeatCount)

    fun shouldConsume(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (!isOk(keyCode)) return false
        return when {
            action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> {
                swallowNextUp = false // a fresh press: forget any stale state
                false
            }
            action == KeyEvent.ACTION_DOWN -> swallowNextUp // also drop further auto-repeats
            action == KeyEvent.ACTION_UP && swallowNextUp -> {
                swallowNextUp = false
                true
            }
            else -> false
        }
    }

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        code == KeyEvent.KEYCODE_BUTTON_A
}
