package com.moonlightpages.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Separate atomic files keep highlights out of EPUB contents and survive process restarts. */
internal class HighlightStore(context: Context) {
    private val root = File(context.filesDir, "highlights")

    private fun file(book: Book) = AtomicFile(bookFile(root, "${book.id}.json"))

    @Synchronized fun load(book: Book): List<BookHighlight> {
        val file = file(book)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val array = file.openRead().bufferedReader().use { JSONArray(it.readText()) }
        return readHighlights(array, book.chapters.size)
    }

    /** Reads saved highlights, checking each one against the book it belongs to. */
    internal fun readHighlights(array: JSONArray, chapters: Int): List<BookHighlight> =
        (0 until array.length()).map { readHighlight(array.getJSONObject(it), chapters) }

    internal fun readHighlight(item: JSONObject, chapters: Int): BookHighlight {
        val passage = SelectedPassage(item.getInt("chapter"),
            readAnchor(item.getJSONObject("start")) ?: error("Invalid highlight start."),
            readAnchor(item.getJSONObject("end")) ?: error("Invalid highlight end."),
            item.getString("text"), item.getDouble("fraction").toFloat(),
            // Older notes predate the chapter name, and read as a place in the file instead.
            cleanChapterName(item.optString("chapterTitle")))
        require(validPassage(passage) && passage.chapter in 0 until chapters) { "Invalid highlight location." }
        return BookHighlight(item.getString("id"), passage, HighlightColor.valueOf(item.getString("color")),
            item.getLong("createdAt"), cleanNote(item.optString("note").orEmpty()))
    }

    @Synchronized fun add(book: Book, passage: SelectedPassage, color: HighlightColor,
                          note: String = ""): List<BookHighlight> {
        require(validPassage(passage) && passage.chapter in book.chapters.indices) { "Select up to 10,000 characters of book text." }
        val marked = passage.copy(title = cleanChapterName(passage.title))
        val current = load(book)
        val written = cleanNote(note)
        val existing = current.find { samePassage(it.passage, marked) }
        // Marking the same words again changes their colour; it must never throw away a note.
        val updated = if (existing != null)
            current.map { if (it.id == existing.id) it.copy(color = color, note = written.ifEmpty { it.note }) else it }
        else listOf(BookHighlight(UUID.randomUUID().toString(), marked, color, System.currentTimeMillis(), written)) + current
        save(book, updated)
        return updated
    }

    /**
     * Changes a saved highlight. A null colour or note leaves that part as it was, so a note can be
     * written, corrected to an empty string, or left alone independently of the colour.
     */
    @Synchronized fun update(book: Book, id: String, color: HighlightColor? = null,
                             note: String? = null): List<BookHighlight> =
        load(book).map {
            if (it.id == id) it.copy(color = color ?: it.color, note = note?.let(::cleanNote) ?: it.note) else it
        }.also { save(book, it) }

    @Synchronized fun recolor(book: Book, id: String, color: HighlightColor): List<BookHighlight> =
        update(book, id, color = color)

    @Synchronized fun remove(book: Book, id: String): List<BookHighlight> =
        load(book).filterNot { it.id == id }.also { save(book, it) }

    /** Erases every highlight saved for a book that has left the shelf. */
    @Synchronized fun removeAll(book: Book) {
        val base = file(book).baseFile
        // A leftover backup or half-written file would otherwise bring the highlights back.
        listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach { it.delete() }
    }

    /**
     * Adds highlights a backup brought. A mark already saved under the same identifier is left
     * exactly as it is, so restoring an older backup can never take back a note written since.
     */
    @Synchronized fun merge(book: Book, incoming: List<BookHighlight>): List<BookHighlight> {
        val current = load(book)
        val fresh = incoming.filterNot { mark -> current.any { it.id == mark.id } }
        if (fresh.isEmpty()) return emptyList()
        save(book, (current + fresh).sortedByDescending { it.createdAt })
        return fresh
    }

    /** Replaces the highlights saved for a book, newest first. */
    internal fun save(book: Book, highlights: List<BookHighlight>) {
        require(root.isDirectory || root.mkdirs()) { "Highlight storage could not be created." }
        val file = file(book)
        val output = file.startWrite()
        try {
            output.write(highlightJsonArray(highlights).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Exception) { file.failWrite(output); throw failure }
    }
}

internal fun highlightJsonArray(highlights: List<BookHighlight>): JSONArray =
    JSONArray().apply { highlights.forEach { put(highlightJson(it)) } }

internal fun highlightJson(mark: BookHighlight): JSONObject = JSONObject()
    .put("id", mark.id).put("chapter", mark.passage.chapter)
    .put("start", writeAnchor(mark.passage.start)).put("end", writeAnchor(mark.passage.end))
    .put("text", mark.passage.text).put("fraction", mark.passage.fraction.toDouble())
    .put("color", mark.color.name).put("createdAt", mark.createdAt).put("note", mark.note)
    .put("chapterTitle", mark.passage.title)

/** A reader's note is their own words, so only its size and loose ends are trimmed. */
internal fun cleanNote(text: String): String = text.trim().take(MAX_NOTE).trim()

/** A chapter name is the book's own words, kept short enough to list. */
internal fun cleanChapterName(text: String): String = text.trim().take(MAX_CHAPTER_NAME).trim()

private const val MAX_NOTE = 2000
private const val MAX_CHAPTER_NAME = 120

