package com.aichat.sandbox.ui.components.notes

/**
 * Phase 10.5 — pure z-order reordering.
 *
 * The editor keeps two z bands (highlighters render in a negative range so
 * they never cover ink — see `NoteEditorViewModel.HIGHLIGHTER_Z_BASE`).
 * Reordering therefore happens *within* each band: the band's existing
 * zIndex values are kept as the slot set and only their assignment to items
 * changes, so bring-to-front can never lift a highlighter above the ink tier
 * and the VM's z counters stay valid.
 */
object ZOrderMath {

    enum class Op { BRING_TO_FRONT, BRING_FORWARD, SEND_BACKWARD, SEND_TO_BACK }

    /** One canvas item: its id, current zIndex, and z band key. */
    data class Entry(val id: String, val zIndex: Int, val band: Int)

    const val BAND_HIGHLIGHTER = 0
    const val BAND_INK = 1

    /**
     * Compute new zIndex values that apply [op] to the [selected] ids.
     * Returns only the items whose zIndex actually changes.
     */
    fun reorder(entries: List<Entry>, selected: Set<String>, op: Op): Map<String, Int> {
        if (selected.isEmpty() || entries.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>()
        for ((_, bandEntries) in entries.groupBy { it.band }) {
            if (bandEntries.none { it.id in selected }) continue
            val sorted = bandEntries.sortedBy { it.zIndex }
            val slots = sorted.map { it.zIndex }
            val reordered = when (op) {
                Op.BRING_TO_FRONT ->
                    sorted.filter { it.id !in selected } + sorted.filter { it.id in selected }
                Op.SEND_TO_BACK ->
                    sorted.filter { it.id in selected } + sorted.filter { it.id !in selected }
                Op.BRING_FORWARD -> stepForward(sorted, selected)
                Op.SEND_BACKWARD -> stepBackward(sorted, selected)
            }
            for (i in reordered.indices) {
                if (reordered[i].zIndex != slots[i]) out[reordered[i].id] = slots[i]
            }
        }
        return out
    }

    /** Each selected item swaps with the first unselected neighbour above it. */
    private fun stepForward(sorted: List<Entry>, selected: Set<String>): List<Entry> {
        val list = sorted.toMutableList()
        for (i in list.size - 2 downTo 0) {
            if (list[i].id in selected && list[i + 1].id !in selected) {
                val tmp = list[i]; list[i] = list[i + 1]; list[i + 1] = tmp
            }
        }
        return list
    }

    /** Each selected item swaps with the first unselected neighbour below it. */
    private fun stepBackward(sorted: List<Entry>, selected: Set<String>): List<Entry> {
        val list = sorted.toMutableList()
        for (i in 1 until list.size) {
            if (list[i].id in selected && list[i - 1].id !in selected) {
                val tmp = list[i]; list[i] = list[i - 1]; list[i - 1] = tmp
            }
        }
        return list
    }
}
