package de.freal.toleo.ui.library

import de.freal.toleo.db.BookWithState
import de.freal.toleo.db.DrmStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RecentlyOpenedTest {
    private val now = ZonedDateTime.of(2026, 11, 17, 12, 0, 0, 0, ZoneOffset.UTC)
    private fun ago(days: Long) = now.minusDays(days).toInstant().toEpochMilli()

    private fun book(
        id: String,
        lastOpened: Long?,
        dismissedAt: Long? = null,
        pinnedAt: Long? = null,
        hidden: Boolean = false,
    ) = BookWithState(
        publicationId = id, title = id, subtitle = null, authors = "", coverUrl = null, format = "EPUB",
        localPath = null, drm = DrmStatus.NONE, isUpload = false, inCloud = true, lastOpened = lastOpened,
        sortIndex = 0, dismissedAt = dismissedAt, pinnedAt = pinnedAt, hidden = hidden, progress = null,
        finished = false, collections = null,
    )

    private fun ids(books: List<BookWithState>) = books.map { it.publicationId }

    @Test fun excludesNeverOpenedAndOlderThanSixMonths() {
        val books = listOf(
            book("never", null),
            book("recent", ago(10)),
            book("edge", now.minusMonths(6).plusMinutes(1).toInstant().toEpochMilli()),
            book("old", now.minusMonths(6).minusDays(1).toInstant().toEpochMilli()),
        )
        assertEquals(listOf("recent", "edge"), ids(recentlyOpened(books, null, now)))
    }

    @Test fun excludesCurrentBook() {
        val current = book("current", ago(0))
        val books = listOf(current, book("a", ago(1)))
        assertEquals(listOf("a"), ids(recentlyOpened(books, current, now)))
    }

    @Test fun excludesDismissedUntilOpenedAgain() {
        val books = listOf(
            book("dismissed", lastOpened = ago(5), dismissedAt = ago(2)),
            book("reopened", lastOpened = ago(1), dismissedAt = ago(3)),
            book("plain", lastOpened = ago(4)),
        )
        assertEquals(listOf("reopened", "plain"), ids(recentlyOpened(books, null, now)))
    }

    @Test fun ordersNewestFirstAndCapsAtThree() {
        val books = listOf(
            book("d", ago(4)),
            book("a", ago(1)),
            book("c", ago(3)),
            book("b", ago(2)),
        )
        assertEquals(listOf("a", "b", "c"), ids(recentlyOpened(books, null, now)))
    }

    @Test fun pinnedBookShowsEvenIfNeverOpenedOldOrDismissed() {
        val books = listOf(
            book("never", null, pinnedAt = ago(1)),
            book("old", now.minusMonths(8).toInstant().toEpochMilli(), pinnedAt = ago(2)),
            book("dismissed", ago(5), dismissedAt = ago(3), pinnedAt = ago(3)),
        )
        assertEquals(listOf("never", "old", "dismissed"), ids(recentlyOpened(books, null, now)))
    }

    @Test fun pinnedBooksComeFirstAndTakeAutomaticSlots() {
        val books = listOf(
            book("a", ago(1)),
            book("b", ago(2)),
            book("c", ago(3)),
            book("pin", ago(40), pinnedAt = ago(0)),
        )
        // One pinned book leaves room for two automatic ones.
        assertEquals(listOf("pin", "a", "b"), ids(recentlyOpened(books, null, now)))
    }

    @Test fun pinnedBooksAreNotCappedAtThree() {
        val books = (1..5).map { book("p$it", null, pinnedAt = ago(it.toLong())) } + book("auto", ago(1))
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), ids(recentlyOpened(books, null, now)))
    }

    @Test fun pinnedCurrentBookStaysOutOfTheShelf() {
        val current = book("current", ago(0), pinnedAt = ago(1))
        assertEquals(emptyList<String>(), ids(recentlyOpened(listOf(current), current, now)))
    }

    @Test fun hiddenBooksNeverShowEvenWhenPinnedOrRecent() {
        val books = listOf(
            book("hidden-recent", ago(1), hidden = true),
            book("hidden-pinned", ago(2), pinnedAt = ago(1), hidden = true),
            book("shown", ago(3)),
        )
        assertEquals(listOf("shown"), ids(recentlyOpened(books, null, now)))
    }

    @Test fun emptyWhenNothingQualifies() {
        assertEquals(emptyList<String>(), ids(recentlyOpened(listOf(book("x", null)), null, now)))
    }
}
