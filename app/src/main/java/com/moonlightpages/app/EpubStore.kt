package com.moonlightpages.app

import android.content.Context
import android.content.res.Configuration
import android.util.AtomicFile
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val chapters: List<String>,
    val cover: String? = null,
    /** Digest of the imported file's own contents, so the very same book is recognisable again. */
    val fingerprint: String? = null,
)

/** What an incoming book means once its contents have been read. */
internal sealed interface Incoming {
    /** The same file is already on the shelf, so open that copy instead of importing a second one. */
    data class Same(val book: Book) : Incoming
    /** A book with the same title and author is already on the shelf; let the reader choose. */
    data class Ask(val existing: Book) : Incoming
    /** Nothing like it is on the shelf. */
    data object Add : Incoming
}

/** How much of a backup's reading state was put back: places moved, bookmarks added. */
internal data class ReadingRestored(val places: Int, val bookmarks: Int)

/**
 * Decides what a book handed to the app from outside means. An exact copy of a file already
 * imported opens that copy, and a file the reader has already matched to a book on the shelf opens
 * that book too. A lookalike is never resolved by guessing, and anything genuinely new is added.
 */
internal fun decide(staged: Book, books: List<Book>, known: Map<String, String> = emptyMap()): Incoming {
    staged.fingerprint?.let { print ->
        books.firstOrNull { it.fingerprint != null && it.fingerprint == print }
            ?.let { return Incoming.Same(it) }
        // The reader has already said this file is a book they have, so stop asking about it.
        books.firstOrNull { it.id == known[print] }?.let { return Incoming.Same(it) }
    }
    // A book whose metadata could not be read carries the same placeholder text as every other one,
    // so those must never be mistaken for each other.
    if (staged.title == "Untitled book" && staged.author == "Unknown author") return Incoming.Add
    val lookalike = books.firstOrNull {
        it.title.equals(staged.title, true) && it.author.equals(staged.author, true)
    }
    return if (lookalike == null) Incoming.Add else Incoming.Ask(lookalike)
}

/** A book's identifier is also a folder name, so it never carries anything a path could act on. */
internal val BOOK_ID = Regex("[A-Za-z0-9_-]+")

// Both ZIP entries and paths inside an EPUB are untrusted input.
internal fun bookFile(root: File, path: String): File {
    // Some tools that zip a book use Windows separators, so a backslash is read as an ordinary
    // separator; the result is then checked for escaping the book exactly like any other path.
    val relative = path.replace('\\', '/')
    require(!relative.startsWith('/')) { "This EPUB has an invalid file layout." }
    val file = File(root, relative).canonicalFile
    require(file.path.startsWith(root.canonicalPath + File.separator)) {
        "This EPUB has an invalid file layout."
    }
    return file
}

internal fun resolveBookPath(root: File, parent: String, href: String): String {
    val uri = URI(href.replace(" ", "%20"))
    require(!uri.isAbsolute && uri.rawAuthority == null) { "External book content is not supported." }
    val decoded = uri.path ?: error("Invalid book link.")
    require(!decoded.startsWith('/')) { "Invalid book path." }
    if (decoded.isEmpty()) return bookFile(root, parent).relativeTo(root.canonicalFile).invariantSeparatorsPath
    val base = File(parent).parent.orEmpty()
    val file = bookFile(root, if (base.isEmpty()) decoded else "$base/$decoded")
    return file.relativeTo(root.canonicalFile).invariantSeparatorsPath
}

/** A saved position this far into the last chapter counts as the end of the book. */
private const val END_OF_BOOK = 0.999f

/** The free, public-domain book the app carries in its own assets. */
private const val BUNDLED_BOOK = "moby-dick.epub"

class EpubStore(private val context: Context) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }
    private val index = AtomicFile(File(context.filesDir, "library.json"))
    private val preferences = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
    internal val highlights = HighlightStore(context)

    fun folder(book: Book) = File(root, book.id)

    fun contents(book: Book) = EpubNavigation.contents(folder(book), book.chapters)

    /**
     * The book's own chapters per section, read once per book and remembered. The shelf asks for a
     * book's progress on every redraw, and reading a book's index to answer that would be wasteful.
     */
    private val plans = HashMap<String, ChapterPlan?>()

    private fun plan(book: Book): ChapterPlan? = synchronized(plans) {
        if (plans.containsKey(book.id)) return plans[book.id]
        val built = runCatching { ChapterPlan.of(contents(book), book.chapters.size) }.getOrNull()
        plans[book.id] = built
        built
    }

    fun bookmarks(book: Book): List<Bookmark> = runCatching {
        readBookmarks(JSONArray(preferences.getString("${book.id}.bookmarks", "[]")), book.chapters.size)
    }.getOrDefault(emptyList())

    /** Reads saved bookmarks, dropping any that no longer point at a place in the book. */
    internal fun readBookmarks(array: JSONArray, chapters: Int): List<Bookmark> = (0 until array.length()).mapNotNull { index ->
        val item = array.getJSONObject(index)
        val chapter = item.getInt("chapter")
        val position = item.getDouble("position").toFloat()
        if (chapter !in 0 until chapters || !position.isFinite()) null
        else Bookmark(item.getString("id"), chapter, position.coerceIn(0f, 1f),
            item.getString("title"), item.getLong("createdAt"), readAnchor(item.optJSONObject("anchor")))
    }

    fun addBookmark(book: Book, chapter: Int, position: Float, title: String, anchor: TextAnchor? = null): List<Bookmark> {
        require(chapter in book.chapters.indices && position.isFinite())
        val current = bookmarks(book)
        val fraction = position.coerceIn(0f, 1f)
        if (current.any { it.chapter == chapter && if (anchor != null) it.anchor == anchor
                else bookmarkAt(listOf(it), chapter, fraction) != null }) return current
        val bookmark = Bookmark(UUID.randomUUID().toString(), chapter, fraction, title.take(300), System.currentTimeMillis(), anchor)
        return (listOf(bookmark) + current).also { saveBookmarks(book, it) }
    }

    fun removeBookmark(book: Book, id: String): List<Bookmark> =
        bookmarks(book).filterNot { it.id == id }.also { saveBookmarks(book, it) }

    fun anchorBookmark(book: Book, id: String, location: ReadingLocation): List<Bookmark> =
        bookmarks(book).map { if (it.id == id) it.copy(position = location.fraction, anchor = location.anchor) else it }
            .also { saveBookmarks(book, it) }

    /** Replaces the bookmarks saved for a book, newest first. */
    internal fun saveBookmarks(book: Book, bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { mark ->
            array.put(JSONObject().put("id", mark.id).put("chapter", mark.chapter)
                .put("position", mark.position.toDouble()).put("title", mark.title).put("createdAt", mark.createdAt)
                .put("anchor", writeAnchor(mark.anchor)))
        }
        preferences.edit().putString("${book.id}.bookmarks", array.toString()).apply()
    }

    /** Reads a shelf index, checking every entry before it is trusted. */
    internal fun readBooks(array: JSONArray): List<Book> = (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        val chapters = item.getJSONArray("chapters")
        require(chapters.length() > 0) { "A saved book has no readable chapters." }
        val id = item.getString("id")
        require(BOOK_ID.matches(id)) { "Invalid saved book identifier." }
        Book(id, item.getString("title"), item.getString("author"),
            (0 until chapters.length()).map { chapters.getString(it) },
            item.optString("cover").takeIf { it.isNotEmpty() },
            item.optString("fingerprint").takeIf { it.isNotEmpty() })
    }

    fun load(): List<Book> {
        if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) return emptyList()
        val array = index.openRead().bufferedReader().use { JSONArray(it.readText()) }
        return readBooks(array).filter { folder(it).isDirectory }.also { books -> books.forEach(::plan) }
    }

    /** The shelf index exactly as it is written to disk, so a backup carries the same words. */
    internal fun libraryJson(books: List<Book>): JSONArray = JSONArray().apply {
        books.forEach { book ->
            put(JSONObject().put("id", book.id).put("title", book.title)
                .put("author", book.author).put("chapters", JSONArray(book.chapters))
                .put("cover", book.cover.orEmpty())
                .put("fingerprint", book.fingerprint.orEmpty()))
        }
    }

    fun save(books: List<Book>) {
        write(index, libraryJson(books).toString())
        // Warming the plans here keeps the shelf's first redraw after an import or a removal from
        // reading a book's index on the main thread.
        books.forEach(::plan)
    }

    private fun write(file: AtomicFile, text: String) {
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized internal fun importIntoLibrary(create: () -> Book): List<Book> {
        // Read before importing: a damaged library must never be replaced by a new empty one, and
        // nothing is unpacked until the shelf is known to be readable.
        val current = load()
        return commit(create(), current)
    }

    /** Puts an already unpacked book on the shelf. */
    @Synchronized internal fun addToLibrary(imported: Book): List<Book> = commit(imported, load())

    private fun commit(imported: Book, current: List<Book>): List<Book> {
        require(current.none { it.id == imported.id }) { "This book is already in the library." }
        return try {
            (listOf(imported) + current).also { save(it) }
        } catch (failure: Exception) {
            // Roll back only this newly imported private directory if committing the index fails.
            bookFile(root, imported.id).deleteRecursively()
            throw failure
        }
    }

    /** Drops a book that was unpacked but never reached the shelf. */
    internal fun discard(book: Book) {
        bookFile(root, book.id).deleteRecursively()
    }

    /**
     * Takes a book off the shelf and erases everything the app kept for it: the imported copy, its
     * reading position, bookmarks and highlights, and any file the reader matched to it. The file
     * the book was imported from is never touched, and no other book is.
     */
    @Synchronized internal fun removeFromLibrary(book: Book): List<Book> {
        val current = load()
        val remaining = current.filterNot { it.id == book.id }
        require(remaining.size != current.size) { "This book is not on your shelf." }
        // The index is the shelf, so it is committed first: if that write fails, nothing has been
        // erased and the book is still there, rather than the shelf naming a book with no copy left.
        save(remaining)
        folder(book).deleteRecursively()
        forget(book)
        synchronized(plans) { plans.remove(book.id) }
        return remaining
    }

    /** Forgets the position, bookmarks, highlights, and file matches saved for one book. */
    private fun forget(book: Book) {
        val editor = preferences.edit()
        listOf("position", "chapter", "anchor", "lastRead", "bookmarks").forEach {
            editor.remove("${book.id}.$it")
        }
        // A file the reader once matched to this book must not keep opening a book that has gone.
        val known = knownFiles().filterValues { it != book.id }
        editor.putString("knownFiles", JSONObject(known).toString()).apply()
        highlights.removeAll(book)
    }

    /** Which book each file the reader has already matched belongs to, keyed by file fingerprint. */
    fun knownFiles(): Map<String, String> = runCatching {
        val json = JSONObject(preferences.getString("knownFiles", "{}").orEmpty())
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    /** Remembers the reader's answer, so this file opens straight into that book from now on. */
    fun rememberCopy(book: Book, fingerprint: String?) {
        if (fingerprint == null) return
        val json = JSONObject()
        knownFiles().forEach { (print, id) -> json.put(print, id) }
        preferences.edit().putString("knownFiles", json.put(fingerprint, book.id).toString()).apply()
    }

    /**
     * Everything the app remembers about reading, as one object for a backup to carry: where each
     * book was left, its bookmarks, the files already matched to a book, and the settings chosen.
     */
    internal fun backupReading(books: List<Book>): JSONObject {
        val places = JSONObject()
        val marks = JSONObject()
        books.forEach { book ->
            val spot = JSONObject().put("chapter", chapter(book))
                .put("position", position(book).toDouble())
                .put("lastRead", lastRead(book))
            writeAnchor(anchor(book))?.let { spot.put("anchor", it) }
            places.put(book.id, spot)
            preferences.getString("${book.id}.bookmarks", null)?.let { marks.put(book.id, JSONArray(it)) }
        }
        return JSONObject().put("places", places).put("bookmarks", marks)
            .put("knownFiles", JSONObject(knownFiles()))
            .put("settings", JSONObject().put("fontSize", fontSize()).put("paper", paper())
                .put("swipeToTurn", swipeToTurn()).put("keepAwake", keepAwake())
                .put("darkTheme", darkTheme()).put("lineSpacing", lineSpacing())
                .put("pageInset", pageInset()).put("pageFont", pageFont()).put("nightDim", nightDim()))
    }

    /**
     * Puts a backup's reading state back beside the books on the shelf. A place only moves when this
     * phone has none yet or when the backup's is more recent, so restoring an old backup never pushes
     * the reader backwards, and bookmarks and file matches are added rather than replaced.
     */
    @Synchronized internal fun restoreReading(reading: JSONObject, shelf: List<Book>): ReadingRestored {
        val savedPlaces = reading.optJSONObject("places") ?: JSONObject()
        val savedMarks = reading.optJSONObject("bookmarks") ?: JSONObject()
        var places = 0
        var added = 0
        shelf.forEach { book ->
            savedPlaces.optJSONObject(book.id)?.let { spot ->
                val at = spot.optLong("lastRead", 0L)
                val chapter = spot.optInt("chapter", 0).coerceIn(0, maxOf(0, book.chapters.lastIndex))
                val fraction = spot.optDouble("position", 0.0).toFloat()
                // A book the backup never actually opened says nothing, so it is left alone.
                val saysSomething = at > 0L || chapter > 0 || fraction > 0f
                val untouched = lastRead(book) == 0L && chapter(book) == 0 && position(book) == 0f
                if (saysSomething && (untouched || at > lastRead(book))) {
                    savePosition(book, chapter, fraction, readAnchor(spot.optJSONObject("anchor")), at)
                    places++
                }
            }
            val incoming = savedMarks.optJSONArray(book.id)?.let { readBookmarks(it, book.chapters.size) }
            val mine = bookmarks(book)
            val fresh = incoming.orEmpty().filterNot { mark -> mine.any { it.id == mark.id } }
            if (fresh.isNotEmpty()) {
                saveBookmarks(book, (mine + fresh).sortedByDescending { it.createdAt })
                added += fresh.size
            }
        }
        // A file already matched to a book on this phone keeps that answer; only new matches are added.
        val known = reading.optJSONObject("knownFiles") ?: JSONObject()
        val here = shelf.map { it.id }.toSet()
        val files = knownFiles().toMutableMap()
        known.keys().forEach { print ->
            val id = known.optString(print)
            if (id.isNotEmpty() && id in here && print !in files) files[print] = id
        }
        val json = JSONObject()
        files.forEach { (print, id) -> json.put(print, id) }
        preferences.edit().putString("knownFiles", json.toString()).apply()
        reading.optJSONObject("settings")?.let { settings ->
            saveAppearance(settings.optInt("fontSize", fontSize()), settings.optInt("paper", paper()),
                settings.optInt("lineSpacing", lineSpacing()), settings.optInt("pageInset", pageInset()),
                settings.optInt("pageFont", pageFont()))
            saveSwipeToTurn(settings.optBoolean("swipeToTurn", swipeToTurn()))
            saveKeepAwake(settings.optBoolean("keepAwake", keepAwake()))
            saveDarkTheme(settings.optBoolean("darkTheme", darkTheme()))
            saveNightDim(settings.optInt("nightDim", nightDim()))
        }
        return ReadingRestored(places, added)
    }

    fun chapter(book: Book) = preferences.getInt("${book.id}.chapter", 0)
        .coerceIn(book.chapters.indices)
    fun position(book: Book) = preferences.getFloat("${book.id}.position", 0f)
    fun anchor(book: Book): TextAnchor? = runCatching {
        readAnchor(JSONObject(preferences.getString("${book.id}.anchor", "{}").orEmpty()))
    }.getOrNull()

    /** Saves where the reader is, stamped with when, unless a restore is putting back an older time. */
    fun savePosition(book: Book, chapter: Int, fraction: Float, anchor: TextAnchor? = null,
                     at: Long = System.currentTimeMillis()) {
        preferences.edit().putInt("${book.id}.chapter", chapter)
            .putFloat("${book.id}.position", fraction.coerceIn(0f, 1f))
            .putString("${book.id}.anchor", writeAnchor(anchor)?.toString())
            .putLong("${book.id}.lastRead", at).apply()
    }

    /**
     * How far through the book the reader is, 0f..1f. The saved position is a section plus a
     * fraction of that section, and the book's own chapters divide those sections, so a novel that
     * packs ten chapters into one file still moves the bar chapter by chapter. A book that names no
     * chapters falls back to counting sections, which is exactly what the plan would have said.
     */
    fun progress(book: Book): Float {
        if (book.chapters.isEmpty()) return 0f
        val chapter = chapter(book).coerceIn(0, book.chapters.lastIndex)
        val fraction = position(book).coerceIn(0f, 1f)
        val plan = plan(book)
        return (plan?.progress(chapter, fraction) ?: (chapter + fraction) / book.chapters.size).coerceIn(0f, 1f)
    }
    fun progressPercent(book: Book) = (progress(book) * 100).roundToInt().coerceIn(0, 100)

    /** A book counts as read once the reader stands on the last page of its last chapter. */
    fun finished(book: Book) = book.chapters.isNotEmpty() &&
        chapter(book) >= book.chapters.lastIndex && position(book) >= END_OF_BOOK

    /** When the book was last opened, used to offer the most recent one to pick up again. */
    fun lastRead(book: Book) = preferences.getLong("${book.id}.lastRead", 0L)

    /**
     * The book to offer resuming: the most recently opened one that has not been finished. A book
     * with a saved position but no timestamp predates the timestamp, so it still counts.
     */
    fun resumeBook(books: List<Book>): Book? = books
        .filter { !finished(it) && (lastRead(it) > 0L || progress(it) > 0f) }
        .maxByOrNull { lastRead(it) }
    fun fontSize() = preferences.getInt("fontSize", 20).coerceIn(14, 32)
    fun paper() = preferences.getInt("paper", 1).coerceIn(0, 2)
    fun lineSpacing() = preferences.getInt("lineSpacing", ReadingTypography.defaultSpacing)
        .coerceIn(0, ReadingTypography.spacings.lastIndex)
    fun pageInset() = preferences.getInt("pageInset", ReadingTypography.defaultInset)
        .coerceIn(0, ReadingTypography.insets.lastIndex)
    fun pageFont() = preferences.getInt("pageFont", ReadingTypography.defaultFont)
        .coerceIn(0, ReadingTypography.fonts.lastIndex)

    /** How dark the reader is dimmed for night reading, as a percentage of black over the page. */
    fun nightDim() = preferences.getInt("nightDim", 0).coerceIn(0, maxNightDim)
    fun saveNightDim(percent: Int) {
        preferences.edit().putInt("nightDim", percent.coerceIn(0, maxNightDim)).apply()
    }

    fun saveAppearance(
        size: Int, paper: Int,
        spacing: Int = ReadingTypography.defaultSpacing,
        inset: Int = ReadingTypography.defaultInset,
        font: Int = ReadingTypography.defaultFont,
    ) {
        preferences.edit().putInt("fontSize", size).putInt("paper", paper)
            .putInt("lineSpacing", spacing).putInt("pageInset", inset).putInt("pageFont", font).apply()
    }

    /**
     * The page turns by swipe or by edge taps, never both. One stored value decides, so the two can
     * never race each other over the same gesture. Swiping is the default; edge taps are the option.
     */
    fun swipeToTurn() = preferences.getBoolean("swipeToTurn", true)
    fun saveSwipeToTurn(enabled: Boolean) {
        preferences.edit().putBoolean("swipeToTurn", enabled).apply()
    }
    fun tapToTurn() = !swipeToTurn()
    fun saveTapToTurn(enabled: Boolean) = saveSwipeToTurn(!enabled)
    fun keepAwake() = preferences.getBoolean("keepAwake", false)
    fun saveKeepAwake(enabled: Boolean) {
        preferences.edit().putBoolean("keepAwake", enabled).apply()
    }

    /**
     * How the shelf is laid out and kept in order. A row a book is the quiet default; the wall of
     * covers is there for a reader who would rather recognise a book than read down a list.
     */
    fun shelfList() = preferences.getBoolean("shelfList", true)
    fun saveShelfList(list: Boolean) {
        preferences.edit().putBoolean("shelfList", list).apply()
    }
    fun shelfOrder() = preferences.getInt("shelfOrder", 0).coerceIn(0, ShelfOrder.entries.lastIndex)
    fun saveShelfOrder(order: Int) {
        preferences.edit().putInt("shelfOrder", order.coerceIn(0, ShelfOrder.entries.lastIndex)).apply()
    }
    /** Which part of the shelf the reader last asked to see. */
    fun shelfFilter() = preferences.getInt("shelfFilter", 0).coerceIn(0, ShelfFilter.entries.lastIndex)
    fun saveShelfFilter(filter: Int) {
        preferences.edit().putInt("shelfFilter", filter.coerceIn(0, ShelfFilter.entries.lastIndex)).apply()
    }

    /**
     * Dark or light. Until the reader chooses, the phone's own day/night setting decides, so the app
     * arrives looking the way the rest of the phone does.
     */
    fun darkTheme(): Boolean = if (preferences.contains("darkTheme")) {
        preferences.getBoolean("darkTheme", false)
    } else {
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
    fun saveDarkTheme(enabled: Boolean) {
        preferences.edit().putBoolean("darkTheme", enabled).apply()
    }

    fun importBook(input: InputStream): Book {
        val id = UUID.randomUUID().toString()
        val folder = File(root, id).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            // A book arrives either as an EPUB (a zip) or as a FictionBook (one XML file), and the
            // file itself says which: neither its name nor the type the system guessed is trusted.
            val buffered = input.buffered(HEAD_BYTES)
            buffered.mark(HEAD_BYTES)
            val head = ByteArray(HEAD_BYTES)
            var filled = 0
            while (filled < HEAD_BYTES) {
                val read = buffered.read(head, filled, HEAD_BYTES - filled)
                if (read < 0) break
                filled += read
            }
            buffered.reset()
            when {
                filled >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() ->
                    extract(buffered, folder, digest)
                MobiBook.looksLikeMobi(head) -> {
                    val mobi = readUpTo(buffered, MobiBook.MAX_BYTES)
                    MobiBook.convert(mobi, folder)
                    digest.update(mobi)
                }
                else -> {
                    val fiction = readUpTo(buffered, Fb2Book.MAX_BYTES)
                    Fb2Book.convert(fiction, folder)
                    digest.update(fiction)
                }
            }
            require(!File(folder, "META-INF/encryption.xml").exists()) {
                "This EPUB contains encrypted content or fonts. Please try an unencrypted, DRM-free EPUB."
            }
            var packagePath: String? = null
            parse(File(folder, "META-INF/container.xml")) { parser ->
                if (parser.name == "rootfile" && packagePath == null) {
                    packagePath = parser.getAttributeValue(null, "full-path")
                }
            }
            val opfPath = packagePath ?: error("This file does not contain a valid EPUB package.")
            val opf = bookFile(folder, opfPath)
            val manifest = mutableMapOf<String, Pair<String, String>>()
            val spine = mutableListOf<String>()
            var title = "Untitled book"
            var author = "Unknown author"
            var coverId: String? = null
            parse(opf) { parser ->
                when (parser.name) {
                    "title" -> title = parser.nextText().trim().ifEmpty { title }
                    "creator" -> author = parser.nextText().trim().ifEmpty { author }
                    "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                        coverId = parser.getAttributeValue(null, "content")
                    }
                    "item" -> {
                        val itemId = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (itemId != null && href != null) {
                            manifest[itemId] = resolveBookPath(folder, opfPath, href) to
                                parser.getAttributeValue(null, "media-type").orEmpty()
                            if (parser.getAttributeValue(null, "properties").orEmpty()
                                    .split(' ').contains("cover-image")) coverId = itemId
                        }
                    }
                    "itemref" -> if (parser.getAttributeValue(null, "linear") != "no") {
                        parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                    }
                }
            }
            val chapters = spine.mapNotNull { manifest[it] }
                .filter { it.second in setOf("application/xhtml+xml", "text/html") }
                .map { it.first }
            require(chapters.isNotEmpty()) { "No readable chapters found. Fixed-layout and DRM-protected books are not supported yet." }
            chapters.forEach { require(bookFile(folder, it).isFile) { "This EPUB is missing a chapter." } }
            val cover = manifest[coverId]?.first?.takeIf { bookFile(folder, it).isFile }
            return Book(id, title, author, chapters, cover, fingerprint(digest))
        } catch (error: Exception) {
            // Only remove the newly-created private import directory, never the source file.
            folder.deleteRecursively()
            throw error
        }
    }

    private fun fingerprint(digest: MessageDigest) =
        digest.digest().joinToString("") { "%02x".format(it) }

    /** Enough of a file's opening to tell what it is: a zip's first two bytes, or the XML root. */
    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val collected = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            collected.write(buffer, 0, read)
            require(collected.size() <= limit) {
                "This book is too large to open: it carries more than ${limit / (1024 * 1024)} MB " +
                    "inside one file."
            }
        }
        return collected.toByteArray()
    }

    private fun extract(input: InputStream, folder: File, digest: MessageDigest) {
        var total = 0L
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 10_000) { "This EPUB has too many files." }
                val file = bookFile(folder, entry.name)
                // The digest covers the book's own contents, so the same file stays recognisable
                // even if it has since been renamed or moved somewhere else on the phone.
                digest.update(entry.name.toByteArray(Charsets.UTF_8))
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    require(!file.exists()) { "This EPUB contains duplicate files." }
                    file.parentFile?.mkdirs()
                    var size = 0L
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            size += read
                            total += read
                            digest.update(buffer, 0, read)
                            require(size <= 32L * 1024 * 1024 && total <= 200L * 1024 * 1024) {
                                "This EPUB is too large (200 MB unpacked limit)."
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun parse(file: File, visit: (XmlPullParser) -> Unit) {
        require(file.isFile) { "This file is not a valid EPUB." }
        require(file.length() <= 4 * 1024 * 1024) { "EPUB metadata is too large." }
        file.inputStream().buffered().use { input ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(input, null)
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) visit(parser)
                parser.next()
            }
        }
    }

    /**
     * The free book Moonlight Pages carries, read from its own assets and imported exactly like a file
     * from the phone. Once it is on the shelf it is an ordinary book: it can be highlighted,
     * bookmarked, finished, and put down again like any other.
     */
    fun bundledBook(): Book = context.assets.open(BUNDLED_BOOK).use { importBook(it) }
}

internal fun writeAnchor(anchor: TextAnchor?): JSONObject? = anchor?.let {
    JSONObject().put("node", it.node).put("offset", it.offset).put("quote", it.quote.take(64))
}

internal fun readAnchor(json: JSONObject?): TextAnchor? {
    if (json == null) return null
    val node = json.optInt("node", -1)
    val offset = json.optInt("offset", -1)
    return if (node < 0 || offset < 0) null else TextAnchor(node, offset, json.optString("quote").take(64))
}

/** How much of a file is read to tell an EPUB from a FictionBook. */
private const val HEAD_BYTES = 4096

