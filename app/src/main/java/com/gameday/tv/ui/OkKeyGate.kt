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

/**
 * Holding Back. A screen that has something for it (the player's guide) sets [action]; holding Back
 * then runs it once Back starts repeating, and the release is dropped so it doesn't also go back.
 * Without an [action], a held Back is an ordinary Back on release.
 */
object BackHold {
    @Volatile
    var action: (() -> Unit)? = null

    @Volatile
    private var fired = false

    /** Returns true if the event was used here (the activity must not treat it as Back). */
    fun onBackKey(action: Int, repeatCount: Int): Boolean = when {
        action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> {
            fired = false
            false
        }
        action == KeyEvent.ACTION_DOWN -> {
            val run = this.action
            if (!fired && run != null) {
                fired = true
                run()
            }
            fired
        }
        action == KeyEvent.ACTION_UP && fired -> {
            fired = false
            true
        }
        else -> false
    }
}

/**
 * When the viewer last pressed a D-pad direction. Tells focus the viewer moved (open the tab they
 * landed on) apart from focus Android moved by itself after the focused item disappeared (don't).
 */
object KeyActivity {
    @Volatile
    private var lastDirectionAt = 0L

    /** Any button, so screens know whether the viewer has started using them. */
    @Volatile
    var lastKeyAt = 0L
        private set

    fun onKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        lastKeyAt = android.os.SystemClock.uptimeMillis()
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                lastDirectionAt = android.os.SystemClock.uptimeMillis()
        }
    }

    fun movedRecently(withinMs: Long = 500): Boolean = android.os.SystemClock.uptimeMillis() - lastDirectionAt < withinMs
}
