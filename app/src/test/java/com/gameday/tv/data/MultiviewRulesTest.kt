package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiviewRulesTest {

    @Test
    fun fillsEmptyVisibleSlotFirst() {
        val r = MultiviewRules.slotForNew(listOf("a", null, null, null), MultiviewLayout.TWO, 0)
        assertEquals(MultiviewRules.AddResult(1, MultiviewLayout.TWO), r)
    }

    @Test
    fun growsLayoutOneScreenAtATime() {
        assertEquals(MultiviewLayout.TWO, MultiviewRules.slotForNew(listOf("a", null, null, null), MultiviewLayout.ONE, 0).layout)
        assertEquals(MultiviewLayout.THREE, MultiviewRules.slotForNew(listOf("a", "b", null, null), MultiviewLayout.TWO, 0).layout)
        val r = MultiviewRules.slotForNew(listOf("a", "b", "c", null), MultiviewLayout.THREE, 0)
        assertEquals(MultiviewRules.AddResult(3, MultiviewLayout.FOUR), r)
    }

    @Test
    fun whenFullReplacesLastScreenWithoutAudio() {
        val full = listOf("a", "b", "c", "d")
        assertEquals(3, MultiviewRules.slotForNew(full, MultiviewLayout.FOUR, 0).slot)
        assertEquals(2, MultiviewRules.slotForNew(full, MultiviewLayout.ONE_PLUS_THREE, 3).slot)
    }

    @Test
    fun compactKeepsOrderAndFollowsAudio() {
        val (slots, audio) = MultiviewRules.compact(listOf(null, "b", null, "d"), 3)
        assertEquals(listOf("b", "d", null, null), slots)
        assertEquals(1, audio)
    }
}
