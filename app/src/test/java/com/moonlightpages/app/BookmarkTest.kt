package com.moonlightpages.app

import org.junit.Assert.*
import org.junit.Test

class BookmarkTest {
    private val mark = Bookmark("one", 1, .5f, "A chapter", 100)

    @Test fun findsSavedPositionInSameChapter() {
        assertEquals(mark, bookmarkAt(listOf(mark), 1, .5001f))
    }

    @Test fun differentChapterIsNotTheSamePlace() {
        assertNull(bookmarkAt(listOf(mark), 0, .5f))
    }

    @Test fun scrollingAwayDoesNotMatchSavedPlace() {
        assertNull(bookmarkAt(listOf(mark), 1, .51f))
    }

    @Test fun emptyListHasNoBookmark() {
        assertNull(bookmarkAt(emptyList(), 1, .5f))
    }
}
