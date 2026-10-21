package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The filters the shelf offers, and the line each row puts under a title. */
class ShelfFilterTest {
    private val unread = Book("a", "Unread Book", "Someone", listOf("one.xhtml"))
    private val reading = Book("b", "Half Read", "Someone", listOf("one.xhtml"))
    private val done = Book("c", "All Done", "Someone", listOf("one.xhtml"))
    private val books = listOf(unread, reading, done)

    private val progress: (Book) -> Float = { when (it.id) { "b" -> .4f; "c" -> 1f; else -> 0f } }
    private val finished: (Book) -> Boolean = { it.id == "c" }

    @Test fun allKeepsTheShelfAsItIs() {
        assertEquals(books, filterBooks(books, ShelfFilter.All, progress, finished))
    }

    @Test fun readingIsABookThatWasOpenedAndNotFinished() {
        assertEquals(listOf("b"), filterBooks(books, ShelfFilter.Reading, progress, finished).map { it.id })
    }

    @Test fun unreadIsABookThatWasNeverOpened() {
        assertEquals(listOf("a"), filterBooks(books, ShelfFilter.Unread, progress, finished).map { it.id })
    }

    @Test fun finishedIsABookOnItsLastPage() {
        assertEquals(listOf("c"), filterBooks(books, ShelfFilter.Finished, progress, finished).map { it.id })
    }

    @Test fun aFinishedBookNeverAlsoCountsAsReadingOrUnread() {
        val seen = ShelfFilter.entries
            .filter { it != ShelfFilter.All }
            .flatMap { filterBooks(books, it, progress, finished).map { book -> book.id } }
        assertEquals(1, seen.count { it == "c" })
    }

    @Test fun aFinishedRowSaysFinishedRatherThanHowFarIn() {
        assertEquals("Finished", shelfMeta(1_700_000_000_000L, 1_700_000_000_000L, 100, finished = true))
    }

    @Test fun aRowInProgressSaysWhenItWasPutDownAndHowFarIn() {
        val now = 1_700_000_000_000L
        assertEquals("Read 2 h ago \u00B7 40 %", shelfMeta(now, now - 2 * 3_600_000L, 40, finished = false))
    }

    @Test fun aRowNeverOpenedSaysSoAndCarriesNoPercentage() {
        assertEquals("Not read yet", shelfMeta(1_700_000_000_000L, 0L, 0, finished = false))
    }
}
