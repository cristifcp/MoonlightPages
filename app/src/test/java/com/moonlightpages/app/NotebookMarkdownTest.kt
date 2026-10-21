package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notebook as a file the reader keeps, outside this app. */
class NotebookMarkdownTest {
    private val hail = Book("one", "Project Hail Mary", "Andy Weir", listOf("one.xhtml"))
    private val dick = Book("two", "Moby Dick", "Herman Melville", listOf("one.xhtml"))

    private fun entry(book: Book, text: String, note: String, chapter: String = "Chapter 3", at: Long = 0L) =
        NotebookEntry(book, BookHighlight("id-$note",
            SelectedPassage(0, TextAnchor(1, 0), TextAnchor(1, 5), text, .2f, chapter),
            HighlightColor.YELLOW, at, note))

    private val written: (BookHighlight) -> String = { "21 October 2026" }

    @Test fun theNotebookIsOneMarkdownFileUnderEachBook() {
        val markdown = notebookMarkdown(listOf(entry(hail, "Some words", "My thought")), written)
        assertTrue(markdown.startsWith("# Notebook"))
        assertTrue(markdown.contains("## Project Hail Mary \u2014 Andy Weir"))
        assertTrue(markdown.contains("### Chapter 3"))
        assertTrue(markdown.contains("> Some words"))
        assertTrue(markdown.contains("My thought"))
        assertTrue(markdown.trimEnd().endsWith("21 October 2026*"))
    }

    @Test fun everyBookGetsItsOwnHeadingAndNotesStayUnderIt() {
        val markdown = notebookMarkdown(
            listOf(entry(hail, "First", "about hail"), entry(dick, "Second", "about whales")), written)
        val hailAt = markdown.indexOf("## Project Hail Mary")
        val dickAt = markdown.indexOf("## Moby Dick")
        val hailNote = markdown.indexOf("about hail")
        val dickNote = markdown.indexOf("about whales")
        assertTrue(hailAt in 0 until dickAt)
        assertTrue(hailNote < dickAt)
        assertTrue(dickNote > dickAt)
    }

    @Test fun aPassageThatRanOverTwoLinesIsQuotedOnOne() {
        val markdown = notebookMarkdown(listOf(entry(hail, "one\ntwo", "note")), written)
        assertTrue(markdown.contains("> one two"))
    }

    @Test fun anEmptyNotebookIsStillAFileThatOpens() {
        assertEquals("# Notebook\n", notebookMarkdown(emptyList(), written))
    }

    @Test fun theSamePassageWrittenTwiceKeepsBothNotes() {
        val markdown = notebookMarkdown(
            listOf(entry(hail, "Words", "first thought"), entry(hail, "Words", "second thought")), written)
        assertTrue(markdown.contains("first thought"))
        assertTrue(markdown.contains("second thought"))
    }
}
