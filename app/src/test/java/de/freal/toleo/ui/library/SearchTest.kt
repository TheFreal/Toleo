package de.freal.toleo.ui.library

import de.freal.toleo.db.BookWithState
import de.freal.toleo.db.DrmStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val book = BookWithState(
        publicationId = "id", title = "Exo: Jumper Book 4", subtitle = null, authors = "Steven Gould", coverUrl = null,
        format = "EPUB", localPath = null, drm = DrmStatus.NONE, isUpload = false, inCloud = true, lastOpened = null,
        sortIndex = 0, dismissedAt = null, pinnedAt = null, hidden = true, progress = null, finished = false,
        collections = null,
    )

    @Test fun blankQueryMatchesEverything() {
        assertTrue(book.matchesQuery(""))
        assertTrue(book.matchesQuery("  "))
    }

    @Test fun matchesTitleOrAuthorIgnoringCase() {
        assertTrue(book.matchesQuery("jumper"))
        assertTrue(book.matchesQuery("GOULD"))
        assertFalse(book.matchesQuery("crichton"))
    }
}
