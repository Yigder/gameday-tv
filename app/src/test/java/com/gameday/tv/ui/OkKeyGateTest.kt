package com.gameday.tv.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulates a real remote: DOWN, auto-repeat DOWNs while held, then UP when released. */
class OkKeyGateTest {

    private fun ok(action: Int, repeat: Int = 0) = OkKeyGate.shouldConsume(KEYCODE_DPAD_CENTER, action, repeat)

    @Test
    fun shortPressPassesThrough() {
        assertFalse(ok(ACTION_DOWN))
        assertFalse(ok(ACTION_UP))
    }

    @Test
    fun releaseAfterLongPressActionIsSwallowed() {
        assertFalse(ok(ACTION_DOWN, 0))
        assertFalse(ok(ACTION_DOWN, 1)) // long-press handler runs on this event...
        OkKeyGate.swallowRelease() // ...and opens new UI
        assertTrue("further repeats must not reach the new UI", ok(ACTION_DOWN, 2))
        assertTrue("the release must not click the newly focused item", ok(ACTION_UP))
        // The next normal press works again.
        assertFalse(ok(ACTION_DOWN))
        assertFalse(ok(ACTION_UP))
    }

    @Test
    fun heldPressWithoutLongPressActionStillReleases() {
        assertFalse(ok(ACTION_DOWN, 0))
        assertFalse(ok(ACTION_DOWN, 1))
        assertFalse(ok(ACTION_UP))
    }

    @Test
    fun staleGateIsClearedByAFreshPress() {
        OkKeyGate.swallowRelease()
        assertFalse(ok(ACTION_DOWN, 0))
        assertFalse(ok(ACTION_UP))
    }

    @Test
    fun otherKeysAreNeverTouched() {
        OkKeyGate.swallowRelease()
        assertFalse(OkKeyGate.shouldConsume(KEYCODE_DPAD_DOWN, ACTION_UP, 0))
        ok(ACTION_DOWN) // reset
    }
}
