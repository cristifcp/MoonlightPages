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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A backup is the reader's books, reading places, bookmarks, highlights and notes, and how they like to
 * read — so these check that all of it travels to another phone, that nothing on the phone it arrives
 * at is ever taken away, and that a file which is not one of our own backups is refused.
 */
@RunWith(AndroidJUnit4::class)
class ReadingBackupTest {
    private lateinit var mine: Phone
    private lateinit var theirs: Phone

    @Before fun setup() {
        mine = Phone("backup-from")
        theirs = Phone("backup-to")
    }

    @After fun cleanup() {
        mine.cleanUp()
        theirs.cleanUp()
    }

    @Test fun aBackupCarriesTheWholeReadingLifeToAnotherPhone() {
        val book = mine.shelfBook()
        val second = mine.shelfBook(groupedChaptersEpubBytes())
        val passage = SelectedPassage(0, TextAnchor(1, 13, "particular"), TextAnchor(1, 23), "particular", .2f,
            "A Quiet Beginning")
        mine.store.savePosition(book, 1, .45f, TextAnchor(1, 13, "particular"))
        mine.store.addBookmark(book, 1, .45f, "One More Page")
        mine.store.highlights.add(book, passage, HighlightColor.MINT, "The quiet I was looking for.")
        mine.store.saveAppearance(26, 2, spacing = 2, inset = 0, font = 1)
        mine.store.saveSwipeToTurn(false)
        mine.store.saveKeepAwake(true)
        mine.store.saveDarkTheme(false)
        mine.store.saveNightDim(40)
        mine.store.rememberCopy(book, "the-file-they-picked")

        val tally = theirs.backup.restore(ByteArrayInputStream(mine.save()))

        assertEquals(BackupTally(books = 2, places = 1, bookmarks = 1, highlights = 1, notes = 1), tally)
        val shelf = theirs.store.load()
        assertEquals(setOf(book.id, second.id), shelf.map { it.id }.toSet())
        val back = shelf.single { it.id == book.id }
        assertEquals("A Quiet Beginning", back.title)
        assertEquals("Moonlight Pages", back.author)
        // The book's own words came with it, so it is still a book that can be read.
        assertEquals(File(mine.store.folder(book), book.chapters[0]).readText(),
            File(theirs.store.folder(back), back.chapters[0]).readText())
        // And so did where the reader was, their bookmark, their note, and how they read.
        assertEquals(1, theirs.store.chapter(back))
        assertEquals(.45f, theirs.store.position(back), .001f)
        assertEquals(TextAnchor(1, 13, "particular"), theirs.store.anchor(back))
        assertEquals(1, theirs.store.bookmarks(back).size)
        val mark = theirs.store.highlights.load(back).single()
        assertEquals(passage, mark.passage)
        assertEquals(HighlightColor.MINT, mark.color)
        assertEquals("The quiet I was looking for.", mark.note)
        assertEquals(26, theirs.store.fontSize())
        assertEquals(2, theirs.store.paper())
        assertEquals("A backup carries how the page was set", 2, theirs.store.lineSpacing())
        assertEquals(0, theirs.store.pageInset())
        assertEquals(1, theirs.store.pageFont())
        assertEquals(40, theirs.store.nightDim())
        assertFalse(theirs.store.swipeToTurn())
        assertTrue(theirs.store.keepAwake())
        assertFalse("A backup carries the theme the reader chose", theirs.store.darkTheme())
        assertEquals(mapOf("the-file-they-picked" to book.id), theirs.store.knownFiles())
    }

    @Test fun aBackupAddsToTheShelfRatherThanReplacingIt() {
        val alreadyHere = theirs.shelfBook()
        theirs.store.savePosition(alreadyHere, 1, .9f)
        val carried = mine.shelfBook(groupedChaptersEpubBytes())

        val tally = theirs.backup.restore(ByteArrayInputStream(mine.save()))

        assertEquals(1, tally.books)
        assertEquals(setOf(alreadyHere.id, carried.id), theirs.store.load().map { it.id }.toSet())
        // The book that was already here, and how far into it the reader had got, are untouched.
        assertEquals(1, theirs.store.chapter(alreadyHere))
        assertEquals(.9f, theirs.store.position(alreadyHere), .001f)
    }

    @Test fun restoringAnOlderBackupNeverMovesAReaderBackwards() {
        val book = mine.shelfBook()
        mine.store.savePosition(book, 0, .2f)
        mine.store.highlights.add(book, passageIn(0), HighlightColor.YELLOW, "An early thought.")
        val older = mine.save()

        // The reader carries on: further into the book, with a newer thought on the same passage.
        mine.store.savePosition(book, 1, .8f)
        val mark = mine.store.highlights.load(book).single()
        mine.store.highlights.update(book, mark.id, note = "A better thought.")

        val tally = mine.backup.restore(ByteArrayInputStream(older))

        assertEquals(1, mine.store.chapter(book))
        assertEquals(.8f, mine.store.position(book), .001f)
        assertEquals("A better thought.", mine.store.highlights.load(book).single().note)
        assertTrue("nothing is taken back", tally.nothing)
    }

    @Test fun restoringTheSameBackupTwiceBringsNothingNewTheSecondTime() {
        val book = mine.shelfBook()
        mine.store.savePosition(book, 1, .4f, TextAnchor(1, 5, "kind"))
        mine.store.addBookmark(book, 1, .4f, "One More Page")
        mine.store.highlights.add(book, passageIn(1), HighlightColor.ROSE, "A note.")
        val saved = mine.save()

        assertEquals(1, theirs.backup.restore(ByteArrayInputStream(saved)).books)
        val again = theirs.backup.restore(ByteArrayInputStream(saved))

        assertTrue("nothing new the second time", again.nothing)
        val back = theirs.store.load().single()
        assertEquals(1, theirs.store.highlights.load(back).size)
        assertEquals(1, theirs.store.bookmarks(back).size)
    }

    @Test fun aFileThatIsNotABackupIsRefusedAndChangesNothing() {
        val book = mine.shelfBook(groupedChaptersEpubBytes())
        val notABackup = listOf(
            "just some text, not even a zip".toByteArray(),
            zipOf(mapOf("holiday-photo.jpg" to "not ours")),
        )
        notABackup.forEach { bytes ->
            val failure = runCatching { mine.backup.restore(ByteArrayInputStream(bytes)) }
            assertEquals(BackupFormat.NOT_A_BACKUP, failure.exceptionOrNull()?.message)
        }
        assertEquals(listOf(book), mine.store.load())
    }

    @Test fun aBackupKeepsABooksOwnLayoutAndItsCover() {
        val book = mine.shelfBook(nestedEpubBytes())
        assertEquals("OEBPS/cover.png", book.cover)

        val tally = theirs.backup.restore(ByteArrayInputStream(mine.save()))

        assertEquals(1, tally.books)
        val back = theirs.store.load().single()
        assertEquals("OEBPS/cover.png", back.cover)
        assertEquals(File(mine.store.folder(book), "OEBPS/text/one.xhtml").readText(),
            File(theirs.store.folder(back), "OEBPS/text/one.xhtml").readText())
        assertTrue("the cover came too", File(theirs.store.folder(back), "OEBPS/cover.png").isFile)
    }

    @Test fun aFileThatIsNotOneOfOurBackupsIsRefusedHoweverItIsShaped() {
        val manifest = BackupFormat.manifest(books = 2, highlights = 5, notes = 1, at = 1234L)
        assertEquals("moonlight-pages", manifest.getString("app"))
        assertEquals(BackupFormat.VERSION, manifest.getInt("format"))
        assertEquals(2, BackupFormat.check(manifest.toString()).getInt("books"))
        // Anything that is not ours, in any shape, is refused the same plain way.
        listOf("not json at all", "", "{}", """{"app":"someone-else"}""").forEach { text ->
            assertEquals("refused: $text", BackupFormat.NOT_A_BACKUP,
                runCatching { BackupFormat.check(text) }.exceptionOrNull()?.message)
        }
    }

    @Test fun aBackupFromANewerMoonlightPagesIsRefused() {
        val newer = zipOf(mapOf(BackupFormat.MANIFEST to BackupFormat.manifest(0, 0, 0, 0L)
            .put("format", BackupFormat.VERSION + 1).toString(), BackupFormat.LIBRARY to "[]"))
        val failure = runCatching { mine.backup.restore(ByteArrayInputStream(newer)) }
        assertEquals("This backup was made by a newer version of Moonlight Pages.", failure.exceptionOrNull()?.message)
        assertTrue(mine.store.load().isEmpty())
    }

    @Test fun aBackupCannotWriteOutsideItsOwnBookFolder() {
        val book = Book("escape-book", "Escape", "Nobody", listOf("one.xhtml"))
        val bytes = zipOf(mapOf(
            BackupFormat.MANIFEST to BackupFormat.manifest(1, 0, 0, 0L).toString(),
            BackupFormat.LIBRARY to mine.store.libraryJson(listOf(book)).toString(),
            "${BackupFormat.BOOKS}${book.id}/one.xhtml" to "the chapter",
            "${BackupFormat.BOOKS}${book.id}/../../escaped.txt" to "this must never land anywhere",
        ))

        val failure = runCatching { mine.backup.restore(ByteArrayInputStream(bytes)) }

        assertNotNull("a file that tries to escape is refused", failure.exceptionOrNull())
        assertFalse(File(mine.folder, "escaped.txt").exists())
        assertFalse(File(mine.folder, "books/${book.id}").exists())
        assertTrue(mine.store.load().isEmpty())
    }

    @Test fun aBookThatDidNotArriveWholeIsNotPutOnTheShelf() {
        val book = Book("half-a-book", "Half a Book", "Nobody", listOf("missing.xhtml"))
        val bytes = zipOf(mapOf(
            BackupFormat.MANIFEST to BackupFormat.manifest(1, 0, 0, 0L).toString(),
            BackupFormat.LIBRARY to mine.store.libraryJson(listOf(book)).toString(),
            "${BackupFormat.BOOKS}${book.id}/other.xhtml" to "not the chapter the index names",
        ))

        val failure = runCatching { mine.backup.restore(ByteArrayInputStream(bytes)) }

        assertTrue("says which book is incomplete",
            failure.exceptionOrNull()?.message.orEmpty().contains("incomplete"))
        assertTrue(mine.store.load().isEmpty())
        assertFalse(File(mine.folder, "books/${book.id}").exists())
    }

    /** Saves everything this phone holds, exactly as the reader's own backup would. */
    private fun Phone.save(): ByteArray = ByteArrayOutputStream().also { backup.save(it) }.toByteArray()

    /** Imports a book the way the app does, so it is on the shelf for a backup to find. */
    private fun Phone.shelfBook(bytes: ByteArray = sampleEpubBytes()): Book =
        store.importIntoLibrary { store.importBook(bytes.inputStream()) }.first()
}

/** One phone's own private storage, so a backup can be made on one and read on another. */
private class Phone(name: String) {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "$name-${UUID.randomUUID()}"
    val folder: File = File(target.cacheDir, key).apply { mkdirs() }
    private val isolated = object : ContextWrapper(target) {
        override fun getFilesDir() = folder
        override fun getCacheDir() = File(folder, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("$key-$name", mode)
    }
    val store = EpubStore(isolated)
    val backup = ReadingBackup(isolated, store)

    fun cleanUp() {
        isolated.getSharedPreferences("reading", Context.MODE_PRIVATE).edit().clear().commit()
        folder.deleteRecursively()
    }
}

/** A passage that lands inside one of the sample book's chapters, for a mark to be saved on. */
private fun passageIn(chapter: Int) = SelectedPassage(
    chapter, TextAnchor(1, 13, "particular"), TextAnchor(1, 23), "particular", .2f, "A Quiet Beginning")

/** A zip file built here, so the tests can hand the app files of their own making. */
private fun zipOf(entries: Map<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}
