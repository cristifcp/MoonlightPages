package com.moonlightpages.app

import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTest {
    private val pride = Book("one", "Pride and Prejudice", "Jane Austen", listOf("one.xhtml"))
    private val alice = Book("two", "Alice in Wonderland", "Lewis Carroll", listOf("one.xhtml"))
    private val books = listOf(pride, alice)

    @Test fun blankSearchShowsAllBooksInOriginalOrder() {
        assertEquals(books, matchingBooks(books, "  "))
    }
    @Test fun titleAndAuthorTermsCanBeCombined() {
        assertEquals(listOf(pride), matchingBooks(books, "  JANE   prejudice "))
    }
    @Test fun supportsAuthorOnlyAndTitleOnlySearches() {
        assertEquals(listOf(alice), matchingBooks(books, "Carroll"))
        assertEquals(listOf(alice), matchingBooks(books, "wonderland"))
    }
    @Test fun everySearchTermMustMatch() {
        assertTrue(matchingBooks(books, "Jane Alice").isEmpty())
    }
}
