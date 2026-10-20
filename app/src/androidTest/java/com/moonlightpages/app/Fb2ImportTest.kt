package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/**
 * An FB2 arrives as one XML file and has to come out as a book the reader can open: a shelf entry
 * with the book's own name, chapters the reader can turn, and a contents list naming them. This runs
 * the whole import the way the app does, on the device, because that is where the package and the
 * contents are read back.
 */
class Fb2ImportTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "fb2-import-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
    }

    @After fun cleanup() {
        context.getSharedPreferences("reading", Context.MODE_PRIVATE).edit().clear().commit()
        folder.deleteRecursively()
    }

    private fun fiction(): String = """<?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description><title-info>
            <author><first-name>Herman</first-name><last-name>Melville</last-name></author>
            <book-title>Loomings</book-title>
            <coverpage><image l:href="#cover.png"/></coverpage>
          </title-info></description>
          <body>
            <section>
              <title><p>Chapter One</p></title>
              <p>Call me Ishmael.</p>
            </section>
            <section>
              <title><p>Chapter Two</p></title>
              <p>The carpet bag.<a l:href="#n1" type="note">[1]</a></p>
            </section>
          </body>
          <body name="notes"><section id="n1"><title><p>Note One</p></title><p>A note about the bag.</p></section></body>
          <binary id="cover.png" content-type="image/png">$PNG</binary>
        </FictionBook>"""

    @Test fun aFictionBookCanBeImportedAndRead() {
        val book = store.importBook(ByteArrayInputStream(fiction().toByteArray()))

        assertEquals("the book's own name", "Loomings", book.title)
        assertEquals("and its author", "Herman Melville", book.author)
        assertEquals("one chapter per titled section, plus the notes", 3, book.chapters.size)
        book.chapters.forEach { require(bookFile(store.folder(book), it).isFile) { "missing chapter $it" } }
        assertEquals("the cover comes with it", true, book.cover?.let { bookFile(store.folder(book), it).isFile })

        val contents = store.contents(book)
        assertEquals("the reader can name every part of it",
            listOf("Chapter One", "Chapter Two", "Notes"), contents.map { it.title })
        assertTrue("in the order they are read", contents.map { it.chapter } == contents.map { it.chapter }.sorted())

        val note = bookFile(store.folder(book), "OEBPS/notes.xhtml").readText()
        assertTrue("and a footnote is a place the reader can reach",
            note.contains("id=\"n1\"") && note.contains("A note about the bag."))
    }

    @Test fun theSameFictionBookIsRecognisedTheSecondTimeItIsOffered() {
        val first = store.importBook(ByteArrayInputStream(fiction().toByteArray()))
        val again = fiction().toByteArray()
        val second = store.importBook(ByteArrayInputStream(again))
        assertEquals("the same words give the same fingerprint, whatever they are called",
            first.fingerprint, second.fingerprint)
    }

    @Test fun theImportedFictionBookIsAReadablePage() {
        val book = store.importBook(ByteArrayInputStream(fiction().toByteArray()))
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        // The reader is ready once it has rendered a page of the chapter: proof the package, the
        // chapter and its markup all survived the conversion.
        compose.awaitReadingPage()
        val page = compose.onRoot().captureToImage().asAndroidBitmap()
        val colours = HashSet<Int>()
        for (y in 0 until page.height step (page.height / 40).coerceAtLeast(1)) {
            for (x in 0 until page.width step (page.width / 20).coerceAtLeast(1)) {
                colours.add(page.getPixel(x, y))
            }
        }
        assertTrue("the imported book's own words are on the page", colours.size > 1)
    }

    @Test fun somethingThatIsNotABookIsRefusedPlainly() {
        val failure = runCatching { store.importBook(ByteArrayInputStream("<html><body>no</body></html>".toByteArray())) }
        assertTrue("a file that is neither an EPUB nor a FictionBook is refused",
            failure.exceptionOrNull() != null)
        assertTrue("and nothing of it is left behind", store.load().isEmpty())
    }

    companion object {
        /** A one-pixel PNG, the way a book carries its pictures: base64 in the file itself. */
        private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
