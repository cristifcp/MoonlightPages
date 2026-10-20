package com.moonlightpages.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The shape of a Moonlight Pages backup: one zip holding the shelf's index, every book as it was imported,
 * the reading places, bookmarks, highlights and notes, and the settings the reader chose. The names
 * live in one place so the writing side and the reading side can never drift apart.
 */
internal object BackupFormat {
    const val VERSION = 1

    // What a backup calls itself, so that it recognises its own file rather than believing whatever
    // is inside it.
    const val APP = "moonlight-pages"
    const val MANIFEST = "moonlight-pages-backup.json"
    const val LIBRARY = "library.json"
    const val READING = "reading.json"
    const val BOOKS = "books/"
    const val HIGHLIGHTS = "highlights/"

    /** Said plainly whenever a file is not ours, rather than blaming the reader for picking it. */
    const val NOT_A_BACKUP = "This file isn’t a Moonlight Pages backup."

    fun manifest(books: Int, highlights: Int, notes: Int, at: Long): JSONObject = JSONObject()
        .put("app", APP).put("format", VERSION).put("createdAt", at)
        .put("books", books).put("highlights", highlights).put("notes", notes)

    /**
     * Checks a backup's manifest before anything inside it is believed. A file that is not one of our
     * own backups is refused plainly, and a newer backup says so rather than being read half way.
     */
    fun check(manifest: String): JSONObject {
        val json = runCatching { JSONObject(manifest) }.getOrNull()
        if (json?.optString("app") != APP) error(NOT_A_BACKUP)
        val format = json.optInt("format", 0)
        require(format >= 1) { NOT_A_BACKUP }
        require(format <= VERSION) { "This backup was made by a newer version of Moonlight Pages." }
        return json
    }

    /** Where a book's own file lives inside a backup, under the identifier it had on the shelf. */
    fun bookEntry(id: String, relative: String) = "$BOOKS$id/$relative"
}

/** What a backup holds, or what a restore brought back. Reads as a sentence for the reader. */
internal data class BackupTally(
    val books: Int = 0,
    val places: Int = 0,
    val bookmarks: Int = 0,
    val highlights: Int = 0,
    val notes: Int = 0,
) {
    internal val nothing: Boolean get() = books + places + bookmarks + highlights + notes == 0

    internal fun describe(): String {
        val parts = ArrayList<String>(5)
        fun part(count: Int, one: String, many: String) {
            if (count > 0) parts += "$count ${if (count == 1) one else many}"
        }
        part(books, "book", "books")
        part(places, "reading place", "reading places")
        part(bookmarks, "bookmark", "bookmarks")
        part(highlights, "highlight", "highlights")
        part(notes, "note", "notes")
        return when (parts.size) {
            0 -> "nothing new"
            1 -> parts.single()
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }
}

/** The name offered when saving a backup, named for the day it was made. */
internal fun backupFileName(today: LocalDate = LocalDate.now()) = "moonlight-pages-backup-$today.zip"

/** The ways a file manager may describe a zip, so a backup stays findable when choosing one back. */
internal val SUPPORTED_BACKUP_TYPES =
    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/**
 * Packs everything the reader has made — the books themselves, where they were left, their bookmarks,
 * highlights and notes, and how they like to read — into one file they choose to keep. A restore puts
 * it back without ever removing anything already on the phone or moving a place they have moved past.
 */
internal class ReadingBackup(private val context: Context, private val store: EpubStore) {
    fun save(destination: OutputStream): BackupTally {
        val books = store.load()
        val marks = books.associateWith { store.highlights.load(it) }
        val tally = BackupTally(books.size,
            places = books.count { store.lastRead(it) > 0L || store.chapter(it) > 0 || store.position(it) > 0f },
            bookmarks = books.sumOf { store.bookmarks(it).size },
            highlights = marks.values.sumOf { it.size },
            notes = marks.values.sumOf { saved -> saved.count { it.hasNote } })
        ZipOutputStream(BufferedOutputStream(destination)).use { zip ->
            zip.putText(BackupFormat.MANIFEST, BackupFormat.manifest(
                tally.books, tally.highlights, tally.notes, System.currentTimeMillis()).toString())
            zip.putText(BackupFormat.LIBRARY, store.libraryJson(books).toString())
            zip.putText(BackupFormat.READING, store.backupReading(books).toString())
            marks.filterValues { it.isNotEmpty() }.forEach { (book, saved) ->
                zip.putText(BackupFormat.HIGHLIGHTS + book.id + ".json", highlightJsonArray(saved).toString())
            }
            books.forEach { putBook(zip, it) }
        }
        return tally
    }

    fun restore(source: InputStream): BackupTally {
        // The file is read through one private copy, so its books can be unpacked in any order.
        val scratch = File(context.cacheDir, "moonlight-pages-restore.zip")
        try {
            source.use { input -> scratch.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_BACKUP) { "This file is too large to be a Moonlight Pages backup." }
                    output.write(buffer, 0, read)
                }
            } }
            return try {
                ZipFile(scratch).use { read(it) }
            } catch (failure: ZipException) {
                // A file that is not a zip at all is refused the same way as one that is not ours.
                error(BackupFormat.NOT_A_BACKUP)
            }
        } finally {
            scratch.delete()
        }
    }

    private fun read(zip: ZipFile): BackupTally {
        BackupFormat.check(zip.text(BackupFormat.MANIFEST) ?: error(BackupFormat.NOT_A_BACKUP))
        val carried = try {
            store.readBooks(JSONArray(zip.text(BackupFormat.LIBRARY) ?: error(BackupFormat.NOT_A_BACKUP)))
        } catch (failure: Exception) {
            error(BackupFormat.NOT_A_BACKUP)
        }
        val here = store.load().map { it.id }.toSet()
        var added = 0
        // A book already on the shelf is left exactly as it is, whatever the backup carries for it.
        carried.filterNot { it.id in here }.forEach { book ->
            unpack(zip, book)
            store.addToLibrary(book)
            added++
        }
        val shelf = store.load()
        val reading = store.restoreReading(JSONObject(zip.text(BackupFormat.READING) ?: "{}"), shelf)
        val carriedIds = carried.map { it.id }.toSet()
        var highlights = 0
        var notes = 0
        shelf.filter { it.id in carriedIds }.forEach { book ->
            val saved = zip.text(BackupFormat.HIGHLIGHTS + book.id + ".json") ?: return@forEach
            val marks = try {
                store.highlights.readHighlights(JSONArray(saved), book.chapters.size)
            } catch (failure: Exception) {
                error(BackupFormat.NOT_A_BACKUP)
            }
            val fresh = store.highlights.merge(book, marks)
            highlights += fresh.size
            notes += fresh.count { it.hasNote }
        }
        return BackupTally(books = added, places = reading.places, bookmarks = reading.bookmarks,
            highlights = highlights, notes = notes)
    }

    /** Every file of an imported book, under the book's own identifier, exactly as it was read. */
    private fun putBook(zip: ZipOutputStream, book: Book) {
        val folder = store.folder(book)
        folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            val relative = file.relativeTo(folder).invariantSeparatorsPath
            zip.putNextEntry(ZipEntry(BackupFormat.bookEntry(book.id, relative)))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /**
     * Unpacks one book from a backup. Every entry must land inside that book's own folder: the name is
     * checked before it is used, so a backup can never write anywhere else on the phone. A book that
     * did not arrive whole is cleaned up again rather than left on the shelf unreadable.
     */
    private fun unpack(zip: ZipFile, book: Book) {
        val folder = store.folder(book)
        // A folder with no place on the shelf is left over from something else, so it starts empty.
        store.discard(book)
        folder.mkdirs()
        val prefix = "${BackupFormat.BOOKS}${book.id}/"
        var total = 0L
        var count = 0
        try {
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }.forEach { entry ->
                require(++count <= MAX_FILES && total <= MAX_BACKUP && entry.size <= MAX_BACKUP) {
                    "This backup is too large to restore."
                }
                val relative = entry.name.removePrefix(prefix)
                require(relative.isNotEmpty() && !relative.startsWith('/') && !relative.contains('\\') &&
                    relative.split('/').none { it.isEmpty() || it == ".." }) { BackupFormat.NOT_A_BACKUP }
                val file = bookFile(folder, relative)
                file.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                total += file.length()
                require(total <= MAX_BACKUP) { "This backup is too large to restore." }
            }
            book.chapters.forEach {
                require(bookFile(folder, it).isFile) { "The backup’s copy of “${book.title}” is incomplete." }
            }
        } catch (failure: Exception) {
            folder.deleteRecursively()
            throw failure
        }
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** Reads one of the small entries a backup carries, refusing an absurdly large one. */
    private fun ZipFile.text(name: String): String? = getEntry(name)?.let { entry ->
        if (entry.size > MAX_ENTRY) error(BackupFormat.NOT_A_BACKUP)
        getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

/** A backup holds only books a phone can hold upright; these keep a stray file from filling it. */
private const val MAX_BACKUP = 400L * 1024 * 1024
private const val MAX_ENTRY = 32L * 1024 * 1024
private const val MAX_FILES = 10_000
