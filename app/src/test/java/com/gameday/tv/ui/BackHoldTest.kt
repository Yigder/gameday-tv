package com.gameday.tv.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DOWN, auto-repeat DOWNs while Back is held, then UP. */
class BackHoldTest {

    @After
    fun clear() {
        BackHold.action = null
    }

    @Test
    fun shortPressIsAnOrdinaryBack() {
        var runs = 0
        BackHold.action = { runs++ }
        assertFalse(BackHold.onBackKey(ACTION_DOWN, 0))
        assertFalse(BackHold.onBackKey(ACTION_UP, 0))
        assertEquals(0, runs)
    }

    @Test
    fun holdingRunsTheActionOnceAndDropsTheRelease() {
        var runs = 0
        BackHold.action = { runs++ }
        assertFalse(BackHold.onBackKey(ACTION_DOWN, 0))
        assertTrue(BackHold.onBackKey(ACTION_DOWN, 1))
        assertTrue(BackHold.onBackKey(ACTION_DOWN, 2))
        assertTrue("the release must not also go back", BackHold.onBackKey(ACTION_UP, 0))
        assertEquals(1, runs)
        // The next press is ordinary again.
        assertFalse(BackHold.onBackKey(ACTION_DOWN, 0))
        assertFalse(BackHold.onBackKey(ACTION_UP, 0))
    }

    @Test
    fun holdingWithoutAnActionStillGoesBackOnRelease() {
        assertFalse(BackHold.onBackKey(ACTION_DOWN, 0))
        assertFalse(BackHold.onBackKey(ACTION_DOWN, 1))
        assertFalse(BackHold.onBackKey(ACTION_UP, 0))
    }
}
