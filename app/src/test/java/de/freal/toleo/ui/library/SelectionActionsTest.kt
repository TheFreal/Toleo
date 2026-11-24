package de.freal.toleo.ui.library

import de.freal.toleo.db.BookWithState
import de.freal.toleo.db.DrmStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionActionsTest {
    private fun book(
        id: String,
        localPath: String? = null,
        finished: Boolean = false,
        hidden: Boolean = false,
        isUpload: Boolean = false,
        inCloud: Boolean = true,
    ) = BookWithState(
        publicationId = id, title = id, subtitle = null, authors = "", coverUrl = null, format = "EPUB",
        localPath = localPath, drm = DrmStatus.NONE, isUpload = isUpload, inCloud = inCloud, lastOpened = null,
        sortIndex = 0, dismissedAt = null, pinnedAt = null, hidden = hidden, progress = null,
        finished = finished, collections = null,
    )

    private fun ids(books: List<BookWithState>) = books.map { it.publicationId }

    @Test fun eachActionOnlyReachesBooksItWouldChange() {
        val onDevice = book("onDevice", localPath = "/x.epub", finished = true)
        val cloudOnly = book("cloudOnly", isUpload = true)
        val localOnly = book("localOnly", localPath = "/y.epub", inCloud = false)
        val hidden = book("hidden", hidden = true)
        val state = LibraryState(selectedBooks = listOf(onDevice, cloudOnly, localOnly, hidden))
        val a = state.selectionActions()
        assertEquals(listOf("cloudOnly", "hidden"), ids(a.download))
        assertEquals(listOf("onDevice", "localOnly"), ids(a.removeDownload))
        assertEquals(listOf("cloudOnly", "localOnly", "hidden"), ids(a.markFinished))
        assertEquals(listOf("onDevice"), ids(a.markUnread))
        assertEquals(listOf("onDevice", "cloudOnly", "localOnly"), ids(a.hide))
        assertEquals(listOf("hidden"), ids(a.unhide))
        assertEquals(listOf("cloudOnly"), ids(a.deleteFromCloud))
    }

    @Test fun skipsDownloadsAlreadyRunning() {
        val state = LibraryState(selectedBooks = listOf(book("a"), book("b")), downloads = mapOf("a" to 0.5f))
        assertEquals(listOf("b"), ids(state.selectionActions().download))
    }

    @Test fun shelfActionsDependOnWhereTheBookIs() {
        val current = book("current")
        val shelved = book("shelved")
        val plain = book("plain")
        val hidden = book("hidden", hidden = true)
        val state = LibraryState(
            continueReading = current,
            recent = listOf(shelved),
            selectedBooks = listOf(current, shelved, plain, hidden),
        )
        val a = state.selectionActions()
        // The current book and hidden books can't go on the shelf; only shelf books can leave it.
        assertEquals(listOf("plain"), ids(a.addToShelf))
        assertEquals(listOf("shelved"), ids(a.dismiss))
    }
}
