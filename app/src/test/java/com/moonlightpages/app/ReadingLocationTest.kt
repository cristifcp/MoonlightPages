package com.moonlightpages.app

import org.junit.Assert.*
import org.junit.Test

class ReadingLocationTest {
    private val mark = Bookmark("id", 0, .25f, "Saved", 0, TextAnchor(4, 30, "a passage"))

    @Test fun matchesTextAnywhereOnTheCurrentPageDespiteDifferentFraction() {
        val page = ReadingLocation(.8f, start = TextAnchor(3, 10), end = TextAnchor(5, 1), page = 8, pages = 11)
        assertEquals(mark, bookmarkOnPage(listOf(mark), 0, page))
    }

    @Test fun pageEndIsExclusive() {
        val page = ReadingLocation(start = TextAnchor(3, 10), end = TextAnchor(4, 30))
        assertNull(bookmarkOnPage(listOf(mark), 0, page))
    }

    @Test fun previousPageDoesNotMatch() {
        val page = ReadingLocation(start = TextAnchor(5, 0), end = TextAnchor(6, 30))
        assertNull(bookmarkOnPage(listOf(mark), 0, page))
    }

    @Test fun anotherChapterDoesNotMatch() {
        val page = ReadingLocation(start = TextAnchor(3, 10), end = TextAnchor(5, 1))
        assertNull(bookmarkOnPage(listOf(mark), 1, page))
    }

    @Test fun legacyBookmarkMapsToNearestPage() {
        val legacy = mark.copy(anchor = null, position = .65f)
        assertEquals(legacy, bookmarkOnPage(listOf(legacy), 0, ReadingLocation(page = 2, pages = 4)))
        assertNull(bookmarkOnPage(listOf(legacy), 0, ReadingLocation(page = 1, pages = 4)))
    }
}
