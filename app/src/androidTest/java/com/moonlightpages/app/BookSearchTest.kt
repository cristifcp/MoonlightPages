package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class BookSearchTest {
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    private lateinit var book: Book
    private lateinit var search: BookSearch

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "book-search-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
        book = sampleBook(store)
        search = BookSearch(store)
    }

    @After fun cleanup() {
        context.getSharedPreferences("reading", Context.MODE_PRIVATE).edit().clear().commit()
        folder.deleteRecursively()
    }

    @Test fun findsMatchesInEveryChapterInReadingOrder() {
        val hits = search.search(book, "window")
        assertEquals(2, hits.size)
        assertEquals(listOf(0, 1), hits.map { it.chapter })
        assertTrue(hits.all { it.text == "window" })
    }

    @Test fun searchIgnoresCaseAndReportsTheOriginalSpelling() {
        val hits = search.search(book, "PARTICULAR")
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.text == "particular" })
        assertTrue(hits.all { it.quote.startsWith("particular") })
    }

    @Test fun aMatchAddressesTheSameTextNodeThePageScriptWalks() {
        val hit = search.search(book, "story is just beginning").first()
        val nodes = ChapterHtml.textNodes(File(store.folder(book), book.chapters[hit.chapter]).readText())
        assertEquals(hit.text, nodes[hit.node].substring(hit.offset, hit.offset + hit.text.length))
        assertTrue(hit.fraction > 0f)
    }

    @Test fun matchesInsideInlineFormattingAreFound() {
        val chapter = File(store.folder(book), "one.xhtml")
        chapter.writeText(chapter.readText().replace("<p>Reading does not ask", "<p>Reading <em>truly</em> does not ask"))
        val hits = BookSearch(store).search(book, "truly")
        assertEquals(1, hits.size)
        assertEquals("truly", hits.single().text)
    }

    @Test fun queriesShorterThanTwoLettersAndEmptyBooksReturnNothing() {
        assertTrue(search.search(book, " ").isEmpty())
        assertTrue(search.search(book, "a").isEmpty())
        assertTrue(search.search(book.copy(id = "missing", chapters = listOf("gone.xhtml")), "window").isEmpty())
    }

    @Test fun aQueryThatNothingContainsFindsNothing() {
        assertTrue(search.search(book, "excelsior").isEmpty())
    }

    @Test fun aHitIsNamedByTheBooksOwnChapterEvenWhenTheFileHoldsSeveral() {
        val chapter = File(store.folder(book), "one.xhtml")
        chapter.writeText("<html><body><p>first lighthouse</p><h2 id='two'>Two</h2><p>second lighthouse</p></body></html>")
        val links = listOf(ChapterLink("Opening", 0), ChapterLink("The Long Road", 0, "two"))
        val hits = search.search(book, "lighthouse", links)
        assertEquals(listOf("Opening", "The Long Road"), hits.map { it.chapterTitle })
    }

    @Test fun resultsAreCappedSoAHugeBookCannotFloodTheSheet() {
        val hits = search.search(book, "the", limit = 3)
        assertEquals(3, hits.size)
    }
}
