package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app decides when a book is handed to it from outside. */
class IncomingTest {
    private fun book(id: String, title: String, author: String, fingerprint: String? = null) =
        Book(id, title, author, listOf("one.xhtml"), fingerprint = fingerprint)

    @Test fun theVerySameFileOpensTheCopyAlreadyOnTheShelf() {
        val shelf = listOf(book("a", "Metro 2033", "Dmitry A Glukhovsky", "abc123"))
        val staged = book("b", "Metro 2033", "Dmitry A Glukhovsky", "abc123")
        assertEquals("a", (decide(staged, shelf) as Incoming.Same).book.id)
    }

    @Test fun aLookalikeFileAsksTheReaderInsteadOfGuessing() {
        val shelf = listOf(book("a", "Metro 2033", "Dmitry A Glukhovsky", "abc123"))
        val staged = book("b", "Metro 2033", "Dmitry A Glukhovsky", "different")
        assertEquals("a", (decide(staged, shelf) as Incoming.Ask).existing.id)
    }

    @Test fun aFileThatMatchesNothingIsSimplyAdded() {
        val shelf = listOf(book("a", "Metro 2033", "Dmitry A Glukhovsky", "abc123"))
        assertSame(Incoming.Add, decide(book("b", "Piranesi", "Susanna Clarke", "zzz"), shelf))
        assertSame(Incoming.Add, decide(book("c", "Metro 2033", "Someone Else", "zzz"), shelf))
        assertSame(Incoming.Add, decide(book("d", "Metro 2034", "Dmitry A Glukhovsky", "zzz"), shelf))
    }

    @Test fun anExactCopyWinsOverADifferentTitle() {
        // The fingerprint identifies the file itself, so it does not depend on readable metadata.
        val shelf = listOf(book("a", "Untitled book", "Unknown author", "abc123"))
        val staged = book("b", "Metro 2033", "Dmitry A Glukhovsky", "abc123")
        assertEquals("a", (decide(staged, shelf) as Incoming.Same).book.id)
    }

    @Test fun booksWithoutReadableMetadataAreNeverTreatedAsEachOther() {
        // Every such book carries the same placeholder text, which is not a real match.
        val shelf = listOf(book("a", "Untitled book", "Unknown author", "one"))
        val staged = book("b", "Untitled book", "Unknown author", "two")
        assertSame(Incoming.Add, decide(staged, shelf))
    }

    @Test fun booksSavedBeforeFingerprintsExistedStillMatchByTitleAndAuthor() {
        val shelf = listOf(book("a", "Metro 2033", "Dmitry A Glukhovsky"))
        val staged = book("b", "Metro 2033", "Dmitry A Glukhovsky", "abc123")
        assertTrue(decide(staged, shelf) is Incoming.Ask)
    }

    @Test fun aFileTheReaderAlreadyMatchedToABookOpensThatBookWithoutAsking() {
        val shelf = listOf(book("a", "Metro 2033", "Dmitry A Glukhovsky", "shelved"))
        val staged = book("b", "Metro 2033", "Dmitry A Glukhovsky", "downloaded-again")
        assertTrue("The first time, it has to ask.", decide(staged, shelf) is Incoming.Ask)
        val known = mapOf("downloaded-again" to "a")
        assertEquals("a", (decide(staged, shelf, known) as Incoming.Same).book.id)
    }

    @Test fun aRememberedFileWhoseBookHasGoneAsksAgain() {
        val shelf = listOf(book("a", "Metro 2033", "Dmitry A Glukhovsky", "shelved"))
        val staged = book("b", "Metro 2033", "Dmitry A Glukhovsky", "downloaded-again")
        assertTrue(decide(staged, shelf, mapOf("downloaded-again" to "gone")) is Incoming.Ask)
    }

    @Test fun theSameBookIsRecognisedWhateverTheCasingOfItsMetadata() {
        val shelf = listOf(book("a", "METRO 2033", "Dmitry A Glukhovsky"))
        val staged = book("b", "Metro 2033", "dmitry a glukhovsky", "abc123")
        assertEquals("a", (decide(staged, shelf) as Incoming.Ask).existing.id)
    }
}
