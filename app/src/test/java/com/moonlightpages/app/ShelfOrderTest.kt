package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shelf's order and the plain words it puts to "when did I read this". Both are pure decisions
 * kept away from the drawing, so they can be checked without a screen.
 */
class ShelfOrderTest {
    private val pride = Book("one", "Pride and Prejudice", "Jane Austen", listOf("one.xhtml"))
    private val alice = Book("two", "Alice in Wonderland", "Lewis Carroll", listOf("one.xhtml"))
    private val emma = Book("three", "emma", "Jane Austen", listOf("one.xhtml"))
    private val books = listOf(pride, alice, emma)

    private val readTimes = mapOf("one" to 300L, "two" to 900L, "three" to 0L)
    private val lastRead: (Book) -> Long = { readTimes[it.id] ?: 0L }

    @Test fun recentOrderPutsTheLastBookReadFirst() {
        assertEquals(listOf("two", "one", "three"), shelfBooks(books, ShelfOrder.Recent, lastRead).map { it.id })
    }

    @Test fun aBookNeverReadComesAfterEveryBookThatHasBeen() {
        val ordered = shelfBooks(books, ShelfOrder.Recent, lastRead)
        assertEquals("three", ordered.last().id)
    }

    @Test fun titleOrderIgnoresCapitals() {
        assertEquals(listOf("two", "three", "one"), shelfBooks(books, ShelfOrder.Title, lastRead).map { it.id })
    }

    @Test fun authorOrderFallsBackToTheTitleSoTheShelfDoesNotShuffle() {
        assertEquals(listOf("three", "one", "two"), shelfBooks(books, ShelfOrder.Author, lastRead).map { it.id })
    }

    @Test fun orderingNeverDropsOrInventsABook() {
        ShelfOrder.entries.forEach { order ->
            assertEquals(books.map { it.id }.sorted(), shelfBooks(books, order, lastRead).map { it.id }.sorted())
        }
    }

    @Test fun aBookWithNoSavedTimeSaysItHasNotBeenRead() {
        assertEquals("Not read yet", lastReadLabel(now = 1_700_000_000_000L, at = 0L))
    }

    @Test fun theFirstTwoMinutesReadAsJustNow() {
        val now = 1_700_000_000_000L
        assertEquals("Read just now", lastReadLabel(now, now))
        assertEquals("Read just now", lastReadLabel(now, now - 60_000L))
        assertEquals("Read just now", lastReadLabel(now, now - 119_000L))
    }

    @Test fun minutesHoursAndDaysAreSaidPlainly() {
        val now = 1_700_000_000_000L
        assertEquals("Read 5 min ago", lastReadLabel(now, now - 5 * 60_000L))
        assertEquals("Read 3 h ago", lastReadLabel(now, now - 3 * 3_600_000L))
        assertEquals("Read yesterday", lastReadLabel(now, now - 30 * 3_600_000L))
        assertEquals("Read 4 days ago", lastReadLabel(now, now - 4 * 86_400_000L))
        assertEquals("Read a month ago", lastReadLabel(now, now - 35 * 86_400_000L))
        assertEquals("Read 3 months ago", lastReadLabel(now, now - 100 * 86_400_000L))
        assertEquals("Read over a year ago", lastReadLabel(now, now - 400 * 86_400_000L))
    }

    @Test fun aClockThatWentBackwardsIsNotReportedAsTheFuture() {
        assertEquals("Read just now", lastReadLabel(now = 1_000L, at = 5_000L))
    }
}
