package de.freal.toleo.ui.library

import de.freal.toleo.db.BookWithState
import de.freal.toleo.db.DrmStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentlyReadingTest {
    private fun book(
        id: String,
        lastOpened: Long?,
        finished: Boolean = false,
        hidden: Boolean = false,
        sortIndex: Int = 0,
    ) = BookWithState(
        publicationId = id, title = id, subtitle = null, authors = "", coverUrl = null, format = "EPUB",
        localPath = null, drm = DrmStatus.NONE, isUpload = false, inCloud = true, lastOpened = lastOpened,
        sortIndex = sortIndex, dismissedAt = null, pinnedAt = null, hidden = hidden, progress = null,
        finished = finished, collections = null,
    )

    @Test fun picksMostRecentlyOpened() {
        val books = listOf(book("older", 100), book("newest", 300), book("middle", 200))
        assertEquals("newest", currentlyReading(books)?.publicationId)
    }

    @Test fun skipsFinishedHiddenAndNeverOpened() {
        val books = listOf(
            book("finished", 500, finished = true),
            book("hidden", 400, hidden = true),
            book("never", null),
            book("reading", 100),
        )
        assertEquals("reading", currentlyReading(books)?.publicationId)
    }

    @Test fun tiesGoToInventoryOrder() {
        val books = listOf(book("second", 100, sortIndex = 2), book("first", 100, sortIndex = 1))
        assertEquals("first", currentlyReading(books)?.publicationId)
    }

    @Test fun noneWhenNothingInProgress() {
        assertNull(currentlyReading(listOf(book("never", null), book("done", 100, finished = true))))
    }
}
