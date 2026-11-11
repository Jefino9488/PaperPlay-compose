package io.max.paperplay.core.account

/**
 * Which songs of an indexed chat are picked for copying. Nothing is picked until the person taps it, whatever the
 * song's state. [pickNew] is an explicit action and leaves out songs already in the main chat or already copied.
 */
class IndexSelection(val picked: Set<Long> = emptySet()) {
    fun toggle(messageId: Long) = IndexSelection(if (messageId in picked) picked - messageId else picked + messageId)

    fun pickNew(songs: List<IndexedSong>) = IndexSelection(picked + songs.filter { !it.inMainChat && !it.copied }.map { it.post.messageId })

    fun clear() = IndexSelection()

    /** The posts to copy: exactly what was picked, in list order. */
    fun toCopy(songs: List<IndexedSong>): List<ChatPost> = songs.filter { it.post.messageId in picked }.map { it.post }
}
