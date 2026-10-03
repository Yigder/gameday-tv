package com.gameday.tv.data

/**
 * Slot bookkeeping for multiview, kept free of Android/Compose so it can be unit tested.
 * There are always 4 slots; the layout decides how many of them are on screen.
 */
object MultiviewRules {

    data class AddResult(val slot: Int, val layout: MultiviewLayout)

    /**
     * Where a new channel goes: an empty visible slot, otherwise grow the layout by one screen,
     * otherwise (already 4 screens) replace the last screen that doesn't have the audio.
     */
    fun <T> slotForNew(slots: List<T?>, layout: MultiviewLayout, audioSlot: Int): AddResult {
        val visible = layout.screens
        (0 until visible).firstOrNull { slots[it] == null }?.let { return AddResult(it, layout) }
        if (visible < slots.size) {
            val bigger = MultiviewLayout.forCount(visible + 1)
            return AddResult(visible, bigger)
        }
        val replace = (visible - 1 downTo 0).first { it != audioSlot }
        return AddResult(replace, layout)
    }

    /**
     * Moves filled slots to the front (keeping their order) so a smaller layout keeps them on screen.
     * Returns the new slot list and where the audio slot moved to.
     */
    fun <T> compact(slots: List<T?>, audioSlot: Int): Pair<List<T?>, Int> {
        val order = slots.indices.filter { slots[it] != null } + slots.indices.filter { slots[it] == null }
        val newSlots = order.map { slots[it] }
        val newAudio = order.indexOf(audioSlot).coerceAtLeast(0)
        return newSlots to newAudio
    }
}
