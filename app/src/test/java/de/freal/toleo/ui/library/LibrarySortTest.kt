package de.freal.toleo.ui.library

import de.freal.toleo.db.BookWithState
import de.freal.toleo.db.DrmStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySortTest {
    private fun book(
        id: String,
        title: String = id,
        authors: String = "",
        lastOpened: Long? = null,
        sortIndex: Int = 0,
        progress: Double? = null,
        finished: Boolean = false,
    ) = BookWithState(
        publicationId = id, title = title, subtitle = null, authors = authors, coverUrl = null, format = "EPUB",
        localPath = null, drm = DrmStatus.NONE, isUpload = false, inCloud = true, lastOpened = lastOpened,
        sortIndex = sortIndex, dismissedAt = null, pinnedAt = null, hidden = false, progress = progress, finished = finished, collections = null,
    )

    private fun order(sort: LibrarySort, reversed: Boolean, books: List<BookWithState>) =
        books.sortedWith(sort.comparator(reversed)).map { it.publicationId }

    @Test fun titleIgnoresCaseAndReverses() {
        val books = listOf(book("b", "banana"), book("c", "Cherry"), book("a", "apple"))
        assertEquals(listOf("a", "b", "c"), order(LibrarySort.TITLE, false, books))
        assertEquals(listOf("c", "b", "a"), order(LibrarySort.TITLE, true, books))
    }

    @Test fun recentKeepsNeverOpenedLastInBothDirections() {
        val books = listOf(book("old", lastOpened = 1), book("never", sortIndex = 0), book("new", lastOpened = 9))
        assertEquals(listOf("new", "old", "never"), order(LibrarySort.RECENT, false, books))
        assertEquals(listOf("old", "new", "never"), order(LibrarySort.RECENT, true, books))
    }

    @Test fun addedFollowsInventoryOrder() {
        val books = listOf(book("b", sortIndex = 1), book("a", sortIndex = 0), book("c", sortIndex = 2))
        assertEquals(listOf("a", "b", "c"), order(LibrarySort.ADDED, false, books))
        assertEquals(listOf("c", "b", "a"), order(LibrarySort.ADDED, true, books))
    }

    @Test fun authorKeepsBlankLastInBothDirections() {
        val books = listOf(book("none", "Z"), book("b", "T", authors = "Bea"), book("a", "T", authors = "adam"))
        assertEquals(listOf("a", "b", "none"), order(LibrarySort.AUTHOR, false, books))
        assertEquals(listOf("b", "a", "none"), order(LibrarySort.AUTHOR, true, books))
    }

    @Test fun progressCountsFinishedAsCompleteAndKeepsUnreadLast() {
        val books = listOf(
            book("half", progress = 0.5),
            book("unread"),
            book("done", progress = 0.2, finished = true),
        )
        assertEquals(listOf("done", "half", "unread"), order(LibrarySort.PROGRESS, false, books))
        assertEquals(listOf("half", "done", "unread"), order(LibrarySort.PROGRESS, true, books))
    }

    @Test fun arrowDirectionFlipsWhenReversed() {
        assertTrue(SortChoice(LibrarySort.TITLE).ascending)
        assertFalse(SortChoice(LibrarySort.TITLE, reversed = true).ascending)
        assertFalse(SortChoice(LibrarySort.RECENT).ascending)
        assertTrue(SortChoice(LibrarySort.RECENT, reversed = true).ascending)
    }
}
