package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class HighlightStoreTest {
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    private lateinit var book: Book
    private val passage = SelectedPassage(0, TextAnchor(1, 13, "particular"), TextAnchor(1, 23), "particular", .2f)

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "highlight-store-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
        book = sampleBook(store)
    }

    @After fun cleanup() {
        context.getSharedPreferences("reading", Context.MODE_PRIVATE).edit().clear().commit()
        folder.deleteRecursively()
    }

    @Test fun persistsRecolorsAndDeletesHighlightsWithoutChangingBookOrProgress() {
        val chapter = File(store.folder(book), book.chapters[0]).readText()
        store.savePosition(book, 1, .7f)
        val marks = store.highlights.add(book, passage, HighlightColor.YELLOW)
        val reopened = HighlightStore(context)
        assertEquals(marks, reopened.load(book))
        val changed = reopened.recolor(book, marks.single().id, HighlightColor.ROSE).single()
        assertEquals(marks.single().id, changed.id)
        assertEquals(passage, changed.passage)
        assertEquals(HighlightColor.ROSE, changed.color)
        assertEquals(1, store.chapter(book))
        assertEquals(.7f, store.position(book), .001f)
        assertEquals(chapter, File(store.folder(book), book.chapters[0]).readText())
        assertTrue(reopened.remove(book, changed.id).isEmpty())
        assertTrue(store.highlights.load(book).isEmpty())
    }

    @Test fun duplicateSelectionUpdatesColorRatherThanDuplicatingIt() {
        val first = store.highlights.add(book, passage, HighlightColor.YELLOW).single()
        val again = store.highlights.add(book, passage.copy(fraction = .8f), HighlightColor.MINT)
        assertEquals(1, again.size)
        assertEquals(first.id, again.single().id)
        assertEquals(HighlightColor.MINT, again.single().color)
    }

    @Test fun highlightsAreIsolatedPerBookAndOverlapsAreAllowed() {
        store.highlights.add(book, passage, HighlightColor.YELLOW)
        store.highlights.add(book, passage.copy(end = TextAnchor(1, 30), text = "particular kind"), HighlightColor.LAVENDER)
        assertEquals(2, store.highlights.load(book).size)
        assertTrue(store.highlights.load(sampleBook(store)).isEmpty())
    }

    @Test fun corruptStorageIsNotOverwrittenByAnEdit() {
        store.highlights.add(book, passage, HighlightColor.YELLOW)
        val file = File(context.filesDir, "highlights/${book.id}.json")
        file.writeText("not valid json")
        try {
            store.highlights.add(book, passage, HighlightColor.MINT)
            fail("Corrupt storage must not be overwritten")
        } catch (_: org.json.JSONException) {
            assertEquals("not valid json", file.readText())
        }
    }

    @Test fun aNoteIsSavedWithItsHighlightReopenedAndChanged() {
        val saved = store.highlights.add(book, passage, HighlightColor.YELLOW, "  The quiet I was looking for.  ")
            .single()
        assertEquals("The quiet I was looking for.", saved.note)
        assertTrue(saved.hasNote)
        val reopened = HighlightStore(context)
        val loaded = reopened.load(book).single()
        assertEquals("The quiet I was looking for.", loaded.note)
        assertEquals(saved, loaded)
        // Marking the same words again in another colour must not throw the note away.
        val recoloured = reopened.add(book, passage, HighlightColor.ROSE).single()
        assertEquals("The quiet I was looking for.", recoloured.note)
        assertEquals(HighlightColor.ROSE, recoloured.color)
        // And the note can be corrected, or taken back, without touching the colour.
        assertEquals("A better thought.", reopened.update(book, saved.id, note = "A better thought.").single().note)
        val cleared = reopened.update(book, saved.id, color = HighlightColor.MINT, note = "").single()
        assertEquals(HighlightColor.MINT, cleared.color)
        assertEquals("", cleared.note)
        assertFalse(cleared.hasNote)
        assertFalse(HighlightStore(context).load(book).single().hasNote)
    }

    @Test fun aNoteIsTrimmedToWhatTheReaderCouldReadBackInFull() {
        val long = "thought ".repeat(500)
        val note = store.highlights.add(book, passage, HighlightColor.YELLOW, long).single().note
        assertTrue("A note is capped at 2000 characters", note.length <= 2000)
        assertEquals("What is saved is what is read back", note, HighlightStore(context).load(book).single().note)
    }

    @Test fun aHighlightKeepsTheBooksOwnNameForItsChapter() {
        val saved = store.highlights.add(book, passage.copy(title = "  CHAPTER 20. All Astir.  "),
            HighlightColor.MINT).single()
        assertEquals("CHAPTER 20. All Astir.", saved.passage.title)
        assertEquals("CHAPTER 20. All Astir.", HighlightStore(context).load(book).single().passage.title)
    }

    @Test fun highlightsSavedBeforeChapterNamesWereKeptStillRead() {
        store.highlights.add(book, passage, HighlightColor.YELLOW)
        val file = File(context.filesDir, "highlights/${book.id}.json")
        val array = org.json.JSONArray(file.readText())
        array.getJSONObject(0).remove("chapterTitle")
        file.writeText(array.toString())
        val loaded = HighlightStore(context).load(book).single()
        assertEquals("", loaded.passage.title)
        assertEquals("Section 1", passageChapter(loaded.passage))
        assertEquals(passage, loaded.passage)
    }
}
