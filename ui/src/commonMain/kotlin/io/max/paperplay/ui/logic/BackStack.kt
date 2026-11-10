package io.max.paperplay.ui.logic

/** Section history. Going to a section already in the history moves it to the top. */
data class BackStack<T>(val items: List<T>) {
    constructor(root: T) : this(listOf(root))

    init { require(items.isNotEmpty()) }

    val current: T get() = items.last()
    val canPop: Boolean get() = items.size > 1

    fun push(item: T): BackStack<T> = if (item == current) this else BackStack(items.filter { it != item } + item)
    fun pop(): BackStack<T> = if (canPop) BackStack(items.dropLast(1)) else this
}
