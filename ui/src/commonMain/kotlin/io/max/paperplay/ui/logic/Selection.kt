package io.max.paperplay.ui.logic

/** Songs picked in multi-select, by id. Long-press a row to start. */
data class Selection(val ids: Set<String> = emptySet()) {
    val active: Boolean get() = ids.isNotEmpty()
    val size: Int get() = ids.size
    operator fun contains(id: String) = id in ids

    fun toggle(id: String) = Selection(if (id in ids) ids - id else ids + id)
    fun selectAll(all: Collection<String>) = Selection(all.toSet())
    fun clear() = Selection()
    fun allSelected(all: Collection<String>) = all.isNotEmpty() && ids.containsAll(all)
    /** Drops picked ids that are no longer in [available]. */
    fun retain(available: Set<String>) = Selection(ids.intersect(available))
}
