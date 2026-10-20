package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubStoreTest {
    private lateinit var folder: File
    private lateinit var store: EpubStore
    private lateinit var isolated: Context

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "epub-test-${UUID.randomUUID()}"
        folder = File(context.cacheDir, key).apply { mkdirs() }
        isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(isolated)
    }

    @After fun cleanup() {
        isolated.getSharedPreferences("reading", Context.MODE_PRIVATE).edit().clear().commit()
        folder.deleteRecursively()
    }

    @Test fun importsSampleAndPersistsLibraryAndPosition() {
        val book = sampleBook(store)
        assertEquals("A Quiet Beginning", book.title)
        assertEquals("Moonlight Pages", book.author)
        assertEquals(2, book.chapters.size)
        store.save(listOf(book))
        store.savePosition(book, 1, .45f)
        val reopened = EpubStore(isolated)
        assertEquals(listOf(book), reopened.load())
        assertEquals(1, reopened.chapter(book))
        assertEquals(.45f, reopened.position(book), .001f)
        assertTrue(bookFile(reopened.folder(book), book.chapters[0]).readText().contains("A Quiet Beginning"))
    }

    @Test fun libraryCanRecoverItsAtomicBackupWhenTheBaseIndexIsMissing() {
        val book = sampleBook(store)
        store.save(listOf(book))
        val index = File(isolated.filesDir, "library.json")
        assertTrue(index.renameTo(File(isolated.filesDir, "library.json.bak")))
        assertEquals(listOf(book), store.load())
        assertTrue(index.exists())
    }

    @Test fun theReadersAnswerAboutAFileSurvivesRestarts() {
        val book = sampleBook(store)
        assertEquals(emptyMap<String, String>(), store.knownFiles())
        store.rememberCopy(book, "first-file")
        store.rememberCopy(book, "second-file")
        assertEquals(mapOf("first-file" to book.id, "second-file" to book.id), store.knownFiles())
        // Reading it from a fresh store, as a restart would, gives the same answer.
        assertEquals(store.knownFiles(), EpubStore(isolated).knownFiles())
    }

    @Test fun aFileWithNoFingerprintIsNotRemembered() {
        val book = sampleBook(store)
        store.rememberCopy(book, null)
        assertEquals(emptyMap<String, String>(), store.knownFiles())
    }

    @Test fun aBookZippedWithWindowsSeparatorsStillOpens() {
        // A book zipped by a Windows tool names its files with backslashes, and must still open.
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF\\container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">windows-zip</dc:identifier><dc:title>Metro 2033</dc:title><dc:creator>Dmitry A Glukhovsky</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""")
            entry("one.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Metro 2033</title></head><body><p>Words.</p></body></html>""")
        }
        val book = store.importBook(bytes.toByteArray().inputStream())
        assertEquals("Metro 2033", book.title)
        assertEquals("Dmitry A Glukhovsky", book.author)
        assertEquals(listOf("one.xhtml"), book.chapters)
        assertTrue("The container lands where the book expects it.",
            File(store.folder(book), "META-INF/container.xml").isFile)
    }

    @Test fun corruptLibraryIsNotReplacedDuringImport() {
        val index = File(isolated.filesDir, "library.json").apply { writeText("invalid library") }
        var created = false
        try {
            store.importIntoLibrary { created = true; sampleBook(store) }
            fail("A corrupt index must be preserved")
        } catch (_: org.json.JSONException) {
            assertFalse(created)
            assertEquals("invalid library", index.readText())
        }
    }

    @Test fun bookmarksPersistWithoutChangingReadingPosition() {
        val book = sampleBook(store)
        val other = sampleBook(store)
        store.savePosition(book, 1, .7f)
        val marks = store.addBookmark(book, 0, .35f, "First chapter")
        assertEquals(1, marks.size)
        assertEquals(marks, store.addBookmark(book, 0, .3501f, "Duplicate"))
        assertTrue(store.bookmarks(other).isEmpty())
        val reopened = EpubStore(isolated)
        assertEquals(marks, reopened.bookmarks(book))
        assertEquals(1, reopened.chapter(book))
        assertEquals(.7f, reopened.position(book), .001f)
        assertTrue(reopened.removeBookmark(book, marks.single().id).isEmpty())
        assertTrue(store.bookmarks(book).isEmpty())
    }

    @Test fun stableTextAnchorsPersistAndLegacyBookmarksCanBeUpgraded() {
        val book = sampleBook(store)
        val anchor = TextAnchor(3, 12, "the words on this page")
        store.savePosition(book, 1, .3f, anchor)
        assertEquals(anchor, EpubStore(isolated).anchor(book))
        val marks = store.addBookmark(book, 1, .3f, "A stable place", anchor)
        assertEquals(anchor, EpubStore(isolated).bookmarks(book).single().anchor)
        assertEquals(marks, store.addBookmark(book, 1, .8f, "Same text, new layout", anchor))
        val legacy = store.addBookmark(book, 0, .6f, "An older bookmark").first()
        val upgraded = store.anchorBookmark(book, legacy.id, ReadingLocation(.5f, anchor))
        assertEquals(anchor, upgraded.first { it.id == legacy.id }.anchor)
        store.savePosition(book, 0, 0f)
        assertNull(store.anchor(book))
    }

    @Test fun existingBooksGetChapterNamesFromHeadings() {
        val book = sampleBook(store)
        assertEquals(listOf("A Quiet Beginning", "One More Page"), store.contents(book).map { it.title })
    }

    @Test fun progressAndFinishedComeFromTheSavedPosition() {
        val book = sampleBook(store)
        assertEquals(0f, store.progress(book), 0f)
        assertFalse(store.finished(book))
        // Half way through the first of two chapters.
        store.savePosition(book, 0, .5f)
        assertEquals(.25f, store.progress(book), .0001f)
        assertEquals(25, store.progressPercent(book))
        // The last page of the last chapter is the end of the book.
        store.savePosition(book, 1, 1f)
        assertEquals(1f, store.progress(book), .0001f)
        assertEquals(100, store.progressPercent(book))
        assertTrue(store.finished(book))
        // Going back into the book un-finishes it.
        store.savePosition(book, 1, .5f)
        assertFalse(store.finished(book))
        assertEquals(75, store.progressPercent(book))
    }

    @Test fun resumeOffersTheMostRecentlyReadBookThatIsNotFinished() {
        val first = sampleBook(store)
        val second = first.copy(id = "second", title = "Second")
        assertNull("Nothing has been opened yet.", store.resumeBook(listOf(first, second)))
        store.savePosition(first, 0, .5f)
        Thread.sleep(5)
        store.savePosition(second, 0, .5f)
        assertEquals(second.id, store.resumeBook(listOf(first, second))?.id)
        store.savePosition(second, 1, 1f)
        assertEquals("A finished book is not offered to carry on with.",
            first.id, store.resumeBook(listOf(first, second))?.id)
        store.savePosition(first, 1, 1f)
        assertNull(store.resumeBook(listOf(first, second)))
        // A position saved before this version recorded a time is still worth offering.
        val legacy = first.copy(id = "legacy", title = "Legacy")
        isolated.getSharedPreferences("reading", Context.MODE_PRIVATE).edit()
            .putInt("legacy.chapter", 0).putFloat("legacy.position", .5f).apply()
        assertEquals("legacy", store.resumeBook(listOf(legacy))?.id)
    }

    @Test fun readsEpub3NavigationWithNestedLabelsAndFragments() {
        val book = sampleBook(store)
        addNavigation(book, "nav.xhtml", "application/xhtml+xml", "nav", """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body><nav epub:type="landmarks"><a href="two.xhtml">Not the contents</a></nav>
            <nav epub:type="toc"><ol><li><a href="one.xhtml">Opening <span>chapter</span></a>
            <ol><li><a href="one.xhtml#middle">A smaller moment</a></li></ol></li>
            <li><a href="two.xhtml#part%20two">The next chapter</a></li></ol></nav></body></html>
        """.trimIndent())
        val contents = store.contents(book)
        assertEquals(listOf("Opening chapter", "A smaller moment", "The next chapter"), contents.map { it.title })
        assertEquals(listOf(0, 1, 0), contents.map { it.depth })
        assertEquals("middle", contents[1].fragment)
        assertEquals("part%20two", contents[2].fragment)
        assertEquals(1, contents[2].chapter)
    }

    @Test fun navigationRejectsExternalAndEscapingLinks() {
        val book = sampleBook(store)
        addNavigation(book, "nav.xhtml", "application/xhtml+xml", "nav", """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body><nav epub:type="toc"><ol>
            <li><a href="https://example.com/book">External</a></li>
            <li><a href="../escape.xhtml">Escape</a></li>
            <li><a href="missing.xhtml">Missing</a></li>
            <li><a href="two.xhtml">Valid</a></li>
            </ol></nav></body></html>
        """.trimIndent())
        assertEquals(listOf("A Quiet Beginning", "Valid"), store.contents(book).map { it.title })
    }

    @Test fun readsLegacyNcxNavigationInDocumentOrder() {
        val book = sampleBook(store)
        addNavigation(book, "toc.ncx", "application/x-dtbncx+xml", "", """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint id="one"><navLabel><text>First &amp; foremost</text></navLabel><content src="one.xhtml"/>
            <navPoint id="sub"><navLabel><text>Inside the first</text></navLabel><content src="one.xhtml#inside"/></navPoint>
            </navPoint><navPoint id="two"><navLabel><text>The second</text></navLabel><content src="two.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent())
        val contents = store.contents(book)
        assertEquals(listOf("First & foremost", "Inside the first", "The second"), contents.map { it.title })
        assertEquals(listOf(0, 1, 0), contents.map { it.depth })
    }

    @Test fun malformedNavigationFallsBackWithoutLosingChapters() {
        val book = sampleBook(store)
        addNavigation(book, "nav.xhtml", "application/xhtml+xml", "nav", "<not-valid")
        assertEquals(2, store.contents(book).size)
        assertEquals("One More Page", store.contents(book)[1].title)
    }

    @Test fun removingABookErasesOnlyThatBookAndLeavesItsSourceFileAlone() {
        val source = File(folder, "source.epub").apply { writeBytes(sampleEpubBytes()) }
        val doomed = store.addToLibrary(store.importBook(source.inputStream())).first()
        val kept = store.addToLibrary(store.importBook(source.inputStream())).first()
        val anchor = TextAnchor(3, 12, "the words on this page")
        val end = TextAnchor(3, 22, "the words")
        store.savePosition(doomed, 1, 0.5f, anchor)
        store.savePosition(kept, 1, 0.25f, anchor)
        store.addBookmark(doomed, 1, 0.4f, "Where I stopped", anchor)
        store.addBookmark(kept, 1, 0.4f, "Where I stopped", anchor)
        store.highlights.add(doomed, SelectedPassage(1, anchor, end, "the words", .4f), HighlightColor.YELLOW)
        store.highlights.add(kept, SelectedPassage(1, anchor, end, "the words", .4f), HighlightColor.MINT)
        store.rememberCopy(doomed, "print-of-the-file-that-went")
        store.rememberCopy(kept, "print-of-the-file-that-stayed")
        val doomedFolder = store.folder(doomed)

        assertEquals(listOf(kept), store.removeFromLibrary(doomed))
        assertEquals(listOf(kept), store.load())
        assertFalse("The imported copy has to go.", doomedFolder.exists())
        assertTrue("A file the book was imported from is not ours to delete.", source.isFile)

        // Nothing about the book that went is left behind to come back later.
        assertEquals(0f, store.progress(doomed), 0f)
        assertEquals(0, store.chapter(doomed))
        assertEquals(0L, store.lastRead(doomed))
        assertNull(store.anchor(doomed))
        assertEquals(emptyList<Bookmark>(), store.bookmarks(doomed))
        assertEquals(emptyList<BookHighlight>(), store.highlights.load(doomed))
        assertEquals(mapOf("print-of-the-file-that-stayed" to kept.id), store.knownFiles())

        // And the book that stayed keeps everything that belonged to it.
        assertTrue(store.folder(kept).isDirectory)
        assertEquals(0.25f, store.position(kept), 0.0001f)
        assertEquals(1, store.bookmarks(kept).size)
        assertEquals(1, store.highlights.load(kept).size)
        assertEquals(HighlightColor.MINT, store.highlights.load(kept).single().color)
    }

    @Test fun removingABookThatIsNotOnTheShelfChangesNothing() {
        val staged = store.importBook(sampleEpubBytes().inputStream())
        val refusal = assertThrows(IllegalArgumentException::class.java) { store.removeFromLibrary(staged) }
        assertEquals("This book is not on your shelf.", refusal.message)
        assertTrue("A staged book is dropped with discard, never taken off the shelf.",
            store.folder(staged).isDirectory)
        assertEquals(emptyList<Book>(), store.load())
    }

    @Test fun removingTheMostRecentlyReadBookHandsResumingToAnother() {
        val first = store.addToLibrary(store.importBook(sampleEpubBytes().inputStream())).first()
        val second = store.addToLibrary(store.importBook(sampleEpubBytes().inputStream())).first()
        store.savePosition(first, 0, 0.2f)
        Thread.sleep(5)
        store.savePosition(second, 0, 0.4f)
        assertEquals(second, store.resumeBook(store.load()))
        store.removeFromLibrary(second)
        assertEquals(first, store.resumeBook(store.load()))
        store.removeFromLibrary(first)
        assertNull(store.resumeBook(store.load()))
    }

    @Test fun theShippedBookIsAWholeNovelWithItsOwnCoverAndNamedChapters() {
        val book = store.bundledBook()
        assertEquals("Moby Dick; Or, The Whale", book.title)
        assertEquals("Herman Melville", book.author)
        assertTrue("A novel arrives as more than one file.", book.chapters.size >= 12)
        val cover = book.cover
        assertNotNull("The shipped book brings its own cover.", cover)
        assertTrue("The cover is unpacked alongside the text.", bookFile(store.folder(book), cover!!).isFile)
        assertEquals(listOf(book), store.addToLibrary(book))

        // The novel's own navigation names every chapter, and the text of chapter one is there.
        val named = store.contents(book).filter { it.title.startsWith("CHAPTER ") }
        assertTrue("Every chapter of the novel is named.", named.size >= 135)
        assertEquals("CHAPTER 1. Loomings.", named.first().title)
        assertTrue(named.all { it.chapter in book.chapters.indices })
        assertTrue("The novel's first chapter really is in the unpacked files.",
            book.chapters.any { bookFile(store.folder(book), it).readText().contains("Call me Ishmael") })
    }

    private fun addNavigation(book: Book, path: String, type: String, properties: String, content: String) {
        File(store.folder(book), path).writeText(content)
        val opf = File(store.folder(book), "book.opf")
        opf.writeText(opf.readText().replace("</manifest>",
            """<item id="navigation" href="$path" media-type="$type" properties="$properties"/></manifest>"""))
    }

    @Test fun rejectsTraversalAndCleansFailedImport() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.txt"))
            zip.write("untrusted".toByteArray())
            zip.closeEntry()
        }
        try {
            store.importBook(bytes.toByteArray().inputStream())
            fail("Traversal ZIP must be rejected")
        } catch (failure: Exception) {
            // Android 14+ can reject the entry before our own canonical-path check.
            assertTrue(failure is IllegalArgumentException || failure is java.util.zip.ZipException)
            assertFalse(File(folder, "books/escape.txt").exists())
            assertEquals(0, File(folder, "books").listFiles()!!.size)
        }
    }

    @Test fun rejectsInvalidBookAndCleansFailedImport() {
        try {
            store.importBook("not an EPUB".byteInputStream())
            fail("Invalid EPUB must be rejected")
        } catch (_: IllegalArgumentException) {
            assertTrue(store.load().isEmpty())
            assertEquals(0, File(folder, "books").listFiles()!!.size)
        }
    }

    @Test fun progressCountsTheChaptersTheBookNamesRatherThanItsFiles() {
        val book = store.importBook(groupedChaptersEpubBytes().inputStream())
        assertEquals(2, book.chapters.size)
        assertEquals(listOf("Chapter One", "Chapter Two", "Chapter Three"),
            store.contents(book).filter { it.chapter == 0 }.map { it.title })
        // Three of the four named chapters live in the first file, so half of that file is three
        // eighths of the book, not half of it.
        store.savePosition(book, 0, .5f)
        assertEquals(.375f, store.progress(book), .001f)
        // The last chapter of the first file is three quarters of the way through the book.
        store.savePosition(book, 0, 1f)
        assertEquals(.75f, store.progress(book), .001f)
        // And a book that names no chapters at all keeps the count it always had.
        val plain = sampleBook(store)
        store.savePosition(plain, 0, .5f)
        assertEquals(.25f, store.progress(plain), .001f)
    }

    @Test fun finishingTheLastFileStillFinishesABookPackagedInFewFiles() {
        val book = store.importBook(groupedChaptersEpubBytes().inputStream())
        store.savePosition(book, book.chapters.lastIndex, .5f)
        assertEquals(.875f, store.progress(book), .001f)
        assertFalse(store.finished(book))
        store.savePosition(book, book.chapters.lastIndex, 1f)
        assertEquals(1f, store.progress(book), .001f)
        assertTrue(store.finished(book))
    }
}
