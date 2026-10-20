package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryViewModelTest {
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "library-model-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
    }
    @After fun cleanup() { folder.deleteRecursively() }

    private fun factory(open: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) }) =
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LibraryViewModel(context, store, open) as T
            }
        }
    private fun await(model: LibraryViewModel, condition: (LibraryState) -> Boolean): LibraryState =
        runBlocking { withTimeout(15_000) { model.state.first(condition) } }

    private fun booksOnDisk() = File(context.filesDir, "books").listFiles()?.size ?: 0

    /** Unpacking and cleanup happen off the main thread, so give the files a moment to settle. */
    private fun awaitBooksOnDisk(count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (booksOnDisk() != count && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(count, booksOnDisk())
    }

    /** A real, minimal EPUB, so the file can be handed to the app the way a file manager would. */
    private fun epubFile(name: String, body: String, title: String = "Metro 2033"): File {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(entryName: String, text: String) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">test-book</dc:identifier><dc:title>$title</dc:title><dc:creator>Dmitry A Glukhovsky</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""")
            entry("one.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1><p>$body</p></body></html>""")
        }
        return File(folder, name).apply { writeBytes(bytes.toByteArray()) }
    }

    @Test fun importingSurvivesActivityRecreationAndCommitsOnlyOnce() {
        val gate = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.importBook {
                check(gate.await(10, TimeUnit.SECONDS))
                sampleBook(store)
            } }
            assertTrue(model.state.value.importing)
            try {
                scenario.recreate()
                scenario.onActivity {
                    assertSame(model, ViewModelProvider(it, factory())[LibraryViewModel::class.java])
                    assertTrue(model.state.value.importing)
                }
            } finally { gate.countDown() }
            val completed = await(model) { !it.importing && it.books.size == 1 }
            assertEquals(completed.books, store.load())
        }
    }

    @Test fun failedLibraryLoadCanBeRetriedWithoutOverwritingTheIndex() {
        val index = File(context.filesDir, "library.json").apply { writeText("broken index") }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            val failed = await(model) { !it.loading }
            assertFalse(failed.available)
            assertTrue(failed.loadFailure)
            scenario.onActivity { model.importSample() }
            assertEquals("broken index", index.readText())
            index.writeText("[]")
            scenario.onActivity { model.reload() }
            assertTrue(await(model) { !it.loading }.available)
        }
    }

    @Test fun aBookHandedToTheAppIsAddedToTheShelfAndOpened() {
        val file = epubFile("metro.epub", "The first version of the words.")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.openIncoming(Uri.fromFile(file)) }
            val opened = await(model) { it.openBookId != null }
            assertNull(opened.message)
            assertFalse(opened.importing)
            assertEquals(1, opened.books.size)
            assertEquals("Metro 2033", opened.books.single().title)
            assertEquals(opened.books.single().id, opened.openBookId)
            assertEquals(opened.books, store.load())
            awaitBooksOnDisk(1)
        }
    }

    @Test fun openingTheSameFileAgainOpensTheCopyAlreadyOnTheShelf() {
        val file = epubFile("metro.epub", "The first version of the words.")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.openIncoming(Uri.fromFile(file)) }
            val first = await(model) { it.openBookId != null }
            scenario.onActivity { model.consumeOpened() }
            assertNull(await(model) { it.openBookId == null }.openBookId)
            scenario.onActivity { model.openIncoming(Uri.fromFile(file)) }
            val again = await(model) { it.openBookId != null }
            assertEquals("The same file must not be imported twice.", first.books, again.books)
            assertEquals("It should open the copy that is already there.", first.openBookId, again.openBookId)
            assertNull(again.duplicate)
            assertEquals(1, store.load().size)
            awaitBooksOnDisk(1)
        }
    }

    @Test fun aLookalikeFileAsksBeforeASecondCopyIsMade() {
        val first = epubFile("metro-1.epub", "Version one of the text.")
        val second = epubFile("metro-2.epub", "Version two, with a different ending.")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.openIncoming(Uri.fromFile(first)) }
            await(model) { it.openBookId != null }
            scenario.onActivity { model.consumeOpened() }
            await(model) { it.openBookId == null }

            scenario.onActivity { model.openIncoming(Uri.fromFile(second)) }
            val asking = await(model) { it.duplicate != null }
            assertEquals("Nothing is added until the reader chooses.", 1, asking.books.size)
            assertEquals("Metro 2033", asking.duplicate!!.staged.title)
            assertEquals(asking.books.single().id, asking.duplicate!!.existing.id)

            // Choosing the copy already on the shelf leaves the shelf exactly as it was.
            scenario.onActivity { model.openExistingCopy() }
            val opened = await(model) { it.openBookId != null }
            assertNull(opened.duplicate)
            assertEquals(1, opened.books.size)
            assertEquals(opened.books.single().id, opened.openBookId)
            assertEquals(1, store.load().size)
            awaitBooksOnDisk(1)
        }
    }

    @Test fun addingASecondCopyKeepsBothBooks() {
        val first = epubFile("metro-1.epub", "Version one of the text.")
        val second = epubFile("metro-2.epub", "Version two, with a different ending.")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.openIncoming(Uri.fromFile(first)) }
            await(model) { it.openBookId != null }
            scenario.onActivity { model.consumeOpened() }
            await(model) { it.openBookId == null }
            scenario.onActivity { model.openIncoming(Uri.fromFile(second)) }
            await(model) { it.duplicate != null }

            scenario.onActivity { model.addSecondCopy() }
            val opened = await(model) { it.openBookId != null && !it.importing }
            assertNull(opened.duplicate)
            assertEquals(2, opened.books.size)
            assertEquals("The file just opened is the one in the reader.", opened.books.first().id, opened.openBookId)
            assertEquals(2, store.load().size)
            awaitBooksOnDisk(2)
        }
    }

    @Test fun choosingNeitherLeavesTheShelfExactlyAsItWas() {
        val first = epubFile("metro-1.epub", "Version one of the text.")
        val second = epubFile("metro-2.epub", "Version two, with a different ending.")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.openIncoming(Uri.fromFile(first)) }
            await(model) { it.openBookId != null }
            scenario.onActivity { model.consumeOpened() }
            await(model) { it.openBookId == null }
            scenario.onActivity { model.openIncoming(Uri.fromFile(second)) }
            await(model) { it.duplicate != null }

            scenario.onActivity { model.cancelIncoming() }
            val settled = await(model) { it.duplicate == null && !it.importing }
            assertNull(settled.openBookId)
            assertEquals(1, settled.books.size)
            assertEquals(1, store.load().size)
            awaitBooksOnDisk(1)
        }
    }

    @Test fun onceTheReaderHasChosenTheCopyOnTheShelfTheSameFileStopsAsking() {
        val first = epubFile("metro-1.epub", "Version one of the text.")
        val second = epubFile("metro-2.epub", "Version two, with a different ending.")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.openIncoming(Uri.fromFile(first)) }
            val shelved = await(model) { it.openBookId != null }.books.single().id
            scenario.onActivity { model.consumeOpened() }
            await(model) { it.openBookId == null }

            scenario.onActivity { model.openIncoming(Uri.fromFile(second)) }
            await(model) { it.duplicate != null }
            scenario.onActivity { model.openExistingCopy() }
            await(model) { it.openBookId != null }
            scenario.onActivity { model.consumeOpened() }
            await(model) { it.openBookId == null }
            awaitBooksOnDisk(1)

            // Opening that same file again goes straight to the book, with no second question.
            scenario.onActivity { model.openIncoming(Uri.fromFile(second)) }
            val again = await(model) { it.openBookId != null }
            assertNull("The reader has already answered for this file.", again.duplicate)
            assertEquals(1, again.books.size)
            assertEquals(shelved, again.openBookId)
            awaitBooksOnDisk(1)
        }
    }

    @Test fun deletingABookTakesItOffTheShelfAndOffTheDisk() {
        val metro = epubFile("metro.epub", "The first version of the words.")
        val other = epubFile("piranesi.epub", "A different book entirely.", title = "Piranesi")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.openIncoming(Uri.fromFile(metro)) }
            await(model) { it.openBookId != null }
            scenario.onActivity { model.consumeOpened() }
            await(model) { it.openBookId == null }
            scenario.onActivity { model.openIncoming(Uri.fromFile(other)) }
            val both = await(model) { it.openBookId != null }
            assertEquals(2, both.books.size)
            val doomed = both.books.first { it.title == "Metro 2033" }

            scenario.onActivity { model.delete(doomed) }
            val left = await(model) { it.books.size == 1 }
            assertNull(left.message)
            assertEquals("Piranesi", left.books.single().title)
            assertEquals(left.books, store.load())
            assertFalse("The imported copy has to go.", store.folder(doomed).isDirectory)
            awaitBooksOnDisk(1)
        }
    }

    @Test fun aRemovalThatCannotHappenLeavesTheShelfAndSaysWhy() {
        val metro = epubFile("metro.epub", "The first version of the words.")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.openIncoming(Uri.fromFile(metro)) }
            val book = await(model) { it.openBookId != null }.books.single()

            scenario.onActivity { model.delete(book) }
            await(model) { it.books.isEmpty() }
            awaitBooksOnDisk(0)

            // Asking again has nothing to remove, which must be said rather than silently ignored.
            scenario.onActivity { model.delete(book) }
            val refused = await(model) { it.message != null }
            assertEquals("Couldn’t remove this book", refused.messageTitle)
            assertEquals("This book is not on your shelf.", refused.message)
            assertTrue(refused.books.isEmpty())
            assertTrue(store.load().isEmpty())
        }
    }

    @Test fun theBookTheAppShipsWithCanBeAddedFromTheEmptyShelf() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            val empty = await(model) { it.available }
            assertTrue(empty.books.isEmpty())

            scenario.onActivity { model.importSample() }
            val shelf = await(model) { it.books.size == 1 }
            assertNull(shelf.message)
            val book = shelf.books.single()
            assertEquals("Moby Dick; Or, The Whale", book.title)
            assertEquals("Herman Melville", book.author)
            assertNotNull("The shelf has a cover to show.", book.cover)
            assertTrue(store.folder(book).isDirectory)
            assertEquals(shelf.books, store.load())
            awaitBooksOnDisk(1)
        }
    }

    @Test fun aFileThatIsNotAnEpubIsRefusedAndChangesNothing() {
        val junk = File(folder, "not-a-book.epub").apply { writeBytes("This is not a ZIP archive at all.".toByteArray()) }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.openIncoming(Uri.fromFile(junk)) }
            val failed = await(model) { !it.importing && it.message != null }
            assertTrue(failed.books.isEmpty())
            assertNull(failed.openBookId)
            assertTrue(store.load().isEmpty())
            // The half-unpacked copy is cleaned up, so retrying with the right file starts fresh.
            awaitBooksOnDisk(0)
        }
    }

    @Test fun aFileHandedOverBeforeTheLibraryHasLoadedIsNotLost() {
        val file = epubFile("metro.epub", "The first version of the words.")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            // Straight away, without waiting for the shelf to finish loading.
            scenario.onActivity { model.openIncoming(Uri.fromFile(file)) }
            val opened = await(model) { it.openBookId != null }
            assertEquals(1, opened.books.size)
            assertEquals(opened.books.single().id, opened.openBookId)
            awaitBooksOnDisk(1)
        }
    }
}
