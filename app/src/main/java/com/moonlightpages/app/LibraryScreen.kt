package com.moonlightpages.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun matchingBooks(books: List<Book>, query: String): List<Book> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return books.filter { book -> words.all { "${book.title} ${book.author}".contains(it, ignoreCase = true) } }
}

/** The ways the shelf can be ordered. [Recent] is the shelf's own order: the last one picked up first. */
internal enum class ShelfOrder(val label: String) {
    Recent("Recently read"), Title("Title"), Author("Author"),
}

/**
 * The shelf in the order the reader asked for. Books never read sort after the ones that have been,
 * and ties fall back to the title so the shelf does not shuffle between redraws.
 */
internal fun shelfBooks(books: List<Book>, order: ShelfOrder, lastRead: (Book) -> Long): List<Book> = when (order) {
    ShelfOrder.Recent -> books.sortedWith(
        compareByDescending<Book> { lastRead(it) }.thenBy { it.title.lowercase() })
    ShelfOrder.Title -> books.sortedBy { it.title.lowercase() }
    ShelfOrder.Author -> books.sortedWith(
        compareBy<Book> { it.author.lowercase() }.thenBy { it.title.lowercase() })
}

/** What the shelf is narrowed to. [All] is the shelf itself, with nothing taken out. */
internal enum class ShelfFilter(val label: String) {
    All("All"), Reading("Reading"), Unread("Unread"), Finished("Finished"),
}

/**
 * The shelf as one filter leaves it. A book is being read once it has been opened and is not
 * finished, and unread until it has been opened at all.
 */
internal fun filterBooks(
    books: List<Book>, filter: ShelfFilter,
    progress: (Book) -> Float, finished: (Book) -> Boolean,
): List<Book> = when (filter) {
    ShelfFilter.All -> books
    ShelfFilter.Reading -> books.filter { !finished(it) && progress(it) > 0f }
    ShelfFilter.Unread -> books.filter { !finished(it) && progress(it) <= 0f }
    ShelfFilter.Finished -> books.filter { finished(it) }
}

/** What a row says about where the reader is: when it was last opened, and how far in. */
internal fun shelfMeta(now: Long, at: Long, percent: Int, finished: Boolean): String = when {
    finished -> "Finished"
    percent > 0 -> "${lastReadLabel(now, at)} · $percent %"
    else -> lastReadLabel(now, at)
}

/**
 * How long ago a book was opened, in the plain words a shelf uses. A book with no saved time at all
 * is one that has never been read, which is said plainly rather than as "never ago".
 */
internal fun lastReadLabel(now: Long, at: Long): String {
    if (at <= 0L) return "Not read yet"
    val elapsed = (now - at).coerceAtLeast(0L)
    val minutes = elapsed / 60_000
    val hours = elapsed / 3_600_000
    val days = elapsed / 86_400_000
    return when {
        minutes < 2 -> "Read just now"
        minutes < 60 -> "Read $minutes min ago"
        hours < 24 -> "Read $hours h ago"
        days < 2 -> "Read yesterday"
        days < 30 -> "Read $days days ago"
        days < 60 -> "Read a month ago"
        days < 365 -> "Read ${days / 30} months ago"
        else -> "Read over a year ago"
    }
}

/**
 * The shelf. A heading and its three actions, the way back into the book that was open last, and a
 * single sheet of paper holding the books themselves: a row a book by default, a wall of covers
 * when the reader asks for one.
 */
@Composable
internal fun LibraryScreen(
    state: LibraryState, store: EpubStore, onImport: () -> Unit,
    onSample: () -> Unit, onRetry: () -> Unit, onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var orderIndex by rememberSaveable { mutableIntStateOf(store.shelfOrder()) }
    var filterIndex by rememberSaveable { mutableIntStateOf(store.shelfFilter()) }
    var shelfList by rememberSaveable { mutableStateOf(store.shelfList()) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val order = ShelfOrder.entries[orderIndex.coerceIn(ShelfOrder.entries.indices)]
    val colours = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // The shelf is stamped once per visit; a row's "read just now" does not need to tick over.
    val now = remember { System.currentTimeMillis() }
    val ordered = remember(state.books, orderIndex) { shelfBooks(state.books, order, store::lastRead) }
    val filter = ShelfFilter.entries[filterIndex.coerceIn(ShelfFilter.entries.indices)]
    val visible = remember(ordered, filterIndex) { filterBooks(ordered, filter, store::progress, store::finished) }
    val filtered = remember(visible, query) { matchingBooks(visible, query) }
    val resume = remember(ordered) { store.resumeBook(ordered) }
    val doomed = pendingDelete?.let { id -> state.books.find { it.id == id } }
    val open = { book: Book -> keyboard?.hide(); focus.clearFocus(); onOpen(book) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 26.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            ShelfActions(
                busy = state.busy, importing = state.importing, searching = searching,
                onImport = { if (!state.busy) onImport() },
                onSearch = { searching = !searching; if (!searching) { query = ""; keyboard?.hide() } },
                list = shelfList,
                onLayout = { shelfList = !shelfList; store.saveShelfList(shelfList) },
            )
        }

        if (resume != null && query.isBlank() && !searching && state.books.isNotEmpty()) {
            ContinueReadingStrip(resume, store.progressPercent(resume)) { open(it) }
        }

        if (searching) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                label = { Text("Search your library") }, placeholder = { Text("Title or author") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search")
                    }
                }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }))
        }

        // One sheet of paper for the whole shelf: it runs to the foot of the screen and the bar
        // stands over it, the way a page continues past what the eye is looking at.
        ShelfSheet(Modifier.weight(1f).padding(start = 14.dp, end = 14.dp, top = 16.dp)) {
            LazyVerticalGrid(
                columns = if (shelfList) GridCells.Fixed(1) else GridCells.Adaptive(136.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 132.dp, start = 6.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(if (shelfList) 0.dp else 20.dp),
            ) {
                item(key = "shelf-heading", span = { GridItemSpan(maxLineSpan) }) {
                    ShelfHeading(order, state.books.size, filtered.size,
                        narrowed = query.isNotBlank() || filter != ShelfFilter.All) {
                        orderIndex = it.ordinal
                        store.saveShelfOrder(it.ordinal)
                    }
                }
                item(key = "shelf-filters", span = { GridItemSpan(maxLineSpan) }) {
                    ShelfFilters(filter) {
                        filterIndex = it.ordinal
                        store.saveShelfFilter(it.ordinal)
                    }
                }
                if (state.loading || state.importing) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.importing) "Bringing your book onto the shelf…" else "Opening your library…",
                            style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
                    }
                }
                if (!state.loading && !state.available) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Let’s try that again.", style = MaterialTheme.typography.headlineSmall)
                        Text("The library couldn’t be loaded. Nothing has been overwritten.",
                            color = colours.onSurfaceVariant)
                        OutlinedButton(onClick = onRetry) { Text("Try again") }
                    }
                }
                if (state.books.isEmpty() && !state.loading && state.available) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyShelf(onImport, onSample, state.busy) }
                }
                if (state.books.isNotEmpty() && filtered.isEmpty()) item(key = "no-results", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Text("No books match your search.", style = MaterialTheme.typography.titleMedium)
                        Text("Try a title, an author, or fewer words.", color = colours.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = { query = "" }) { Text("Show all books") }
                    }
                }
                items(filtered, key = { it.id }, span = { if (shelfList) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { book ->
                    if (shelfList) BookRow(book, store, now, onOpen = { open(it) }, onLongPress = { pendingDelete = it.id })
                    else BookCard(book, store, onOpen = { open(it) }, onLongPress = { pendingDelete = it.id })
                }
            }
        }
    }
    doomed?.let { book ->
        DeleteBookDialog(book, onDelete = { pendingDelete = null; onDelete(it) }, onKeep = { pendingDelete = null })
    }
}

/**
 * The three things the shelf is for: bringing a book in, finding one already there, and choosing
 * how the books are laid out. They sit in one shell so they read as a single thing to reach for.
 */
@Composable
private fun ShelfActions(
    busy: Boolean, importing: Boolean, searching: Boolean,
    onImport: () -> Unit, onSearch: () -> Unit, list: Boolean, onLayout: () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(colours.background.copy(alpha = .55f))
        .border(1.dp, colours.outlineVariant.copy(alpha = .55f), shape).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ShellAction(
            contentDescription = if (importing) "Importing a book" else "Import a book",
            enabled = !busy, onClick = onImport,
        ) {
            if (importing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colours.primary)
            else Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = colours.primary)
        }
        ShellDivider()
        ShellAction(contentDescription = "Search your library", onClick = onSearch) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null,
                tint = if (searching) colours.primary else colours.primary.copy(alpha = .75f))
        }
        ShellDivider()
        ShellAction(
            contentDescription = if (list) "Show covers" else "Show as a list", onClick = onLayout,
        ) {
            Icon(painterResource(if (list) R.drawable.ic_grid else R.drawable.ic_contents),
                contentDescription = null, tint = colours.primary.copy(alpha = .75f))
        }
    }
}

/** One tap target of the action shell. The slot is the same size whether it holds an icon or a spinner. */
@Composable
private fun ShellAction(
    contentDescription: String, enabled: Boolean = true, onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(50))
        .clickable(enabled = enabled, onClick = onClick)
        .semantics {
            this.contentDescription = contentDescription
            if (!enabled) disabled()
        }, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ShellDivider() {
    Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)))
}

/**
 * The label the order of the shelf is kept under, which is also where a different order is asked
 * for. It doubles as the count, so the shelf answers "how many" where it is already talking about
 * which books these are.
 */
@Composable
private fun ShelfHeading(
    order: ShelfOrder, books: Int, shown: Int, narrowed: Boolean, onOrder: (ShelfOrder) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    val colours = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { choosing = true }
                .semantics { contentDescription = "Shelf order: ${order.label}. Change the order" }
                .padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(order.label.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.8.sp),
                    color = colours.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null,
                    tint = colours.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                ShelfOrder.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) },
                        onClick = { choosing = false; onOrder(option) })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            when {
                narrowed -> "$shown of $books"
                books == 1 -> "1 book"
                else -> "$books books"
            },
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = .6.sp),
            color = colours.onSurfaceVariant,
        )
    }
}

/**
 * The shelf narrowed by what the reader is looking for: everything, what is being read, what has
 * not been opened, and what is finished. A chip is a quiet pill rather than a button, so the shelf
 * keeps its calm.
 */
@Composable
private fun ShelfFilters(current: ShelfFilter, onFilter: (ShelfFilter) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfFilter.entries.forEach { option ->
            val active = option == current
            val shape = RoundedCornerShape(50)
            Box(Modifier.clip(shape)
                .background(if (active) colours.primary.copy(alpha = .16f) else Color.Transparent)
                .border(1.dp, if (active) Color.Transparent else colours.outlineVariant.copy(alpha = .55f), shape)
                .clickable { onFilter(option) }
                .semantics {
                    selected = active
                    contentDescription = "Show ${option.label} books"
                }
                .padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text(option.label, style = MaterialTheme.typography.labelMedium,
                    color = if (active) colours.primary else colours.onSurfaceVariant)
            }
        }
    }
}

/** The way back into the book that was open last: named, and how far in, in one line. */
@Composable
private fun ContinueReadingStrip(book: Book, percent: Int, onOpen: (Book) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
        .clip(RoundedCornerShape(14.dp)).clickable { onOpen(book) }
        .semantics(mergeDescendants = true) {
            contentDescription = "Continue reading ${book.title}" + if (percent > 0) " at $percent%" else ""
        }
        .padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_bookmark_filled), contentDescription = null,
            tint = colours.primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = colours.onSurfaceVariant)) { append("Continue reading ") }
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colours.onSurface)) { append(book.title) }
            if (percent > 0) withStyle(SpanStyle(color = colours.onSurfaceVariant)) { append(" · $percent %") }
        }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A shelf with nothing on it yet. The card is the invitation. */
@Composable
private fun EmptyShelf(onImport: () -> Unit, onSample: () -> Unit, busy: Boolean) {
    val colours = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.reader_mark), contentDescription = null,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
        Text("Good stories start here.", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 20.dp))
        Text("Bring a DRM-free EPUB from your phone.\nYour books stay on this device.",
            textAlign = TextAlign.Center, color = colours.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 22.dp))
        Button(onClick = onImport, enabled = !busy) { Text("+  Import your first book") }
        TextButton(onClick = onSample, enabled = !busy) { Text("Or start with Moby Dick") }
        Text("Offline by design · No account required", style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant, modifier = Modifier.padding(top = 22.dp))
    }
}

/**
 * Taking a book off the shelf erases the copy Moonlight Pages made along with everything saved in it, so
 * it never happens without being asked for. The file the book came from is not the app's to delete.
 */
@Composable
internal fun DeleteBookDialog(book: Book, onDelete: (Book) -> Unit, onKeep: () -> Unit) {
    AlertDialog(onDismissRequest = onKeep,
        title = { Text("Delete “${book.title}”?") },
        text = {
            Text("This erases the copy Moonlight Pages made, along with your reading position, bookmarks " +
                "and highlights for it. The file you imported from stays on your phone.")
        },
        confirmButton = {
            TextButton(onClick = { onDelete(book) }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onKeep) { Text("Keep it") } })
}

private object Covers {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    fun cached(file: File) = cache.get(file.path)
    fun load(file: File): Bitmap? {
        cached(file)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 600 || bounds.outHeight / inSampleSize > 900) inSampleSize *= 2
        }
        return BitmapFactory.decodeFile(file.path, options)?.also { cache.put(file.path, it) }
    }
}

@Composable
private fun rememberCover(book: Book, store: EpubStore): Bitmap? {
    val file = remember(book.id, book.cover) { book.cover?.let { bookFile(store.folder(book), it) } }
    val cover by produceState(file?.let(Covers::cached), file?.path) {
        if (value == null && file != null) value = withContext(Dispatchers.IO) { runCatching { Covers.load(file) }.getOrNull() }
    }
    return cover
}

/** A book as a row of the shelf: cover, what it is, and how long ago it was put down. */
@Composable
private fun BookRow(book: Book, store: EpubStore, now: Long, onOpen: (Book) -> Unit, onLongPress: (Book) -> Unit) {
    val colours = MaterialTheme.colorScheme
    val cover = rememberCover(book, store)
    val lastRead = remember(book.id) { store.lastRead(book) }
    val finished = remember(book.id) { store.finished(book) }
    val percent = remember(book.id) { store.progressPercent(book) }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.Top) {
        // The book itself is one thing to a screen reader; the menu beside it stays its own.
        Row(Modifier.weight(1f)
            .combinedClickable(onClick = { onOpen(book) }, onLongClick = { onLongPress(book) })
            .semantics(mergeDescendants = true) {
                contentDescription = "${book.title}, by ${book.author}" + if (finished) ", finished" else ""
                // When it was last put down is state, not identity, so it is said in its own place
                // rather than folded into the book's name.
                stateDescription = shelfMeta(now, lastRead, percent, finished)
                customActions = listOf(CustomAccessibilityAction("Remove this book") { onLongPress(book); true })
            }
            .padding(start = 18.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            BookCover(book, cover, Modifier.width(44.dp).height(66.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(book.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author, style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                Text(shelfMeta(now, lastRead, percent, finished), style = MaterialTheme.typography.bodySmall,
                    color = colours.onSurfaceVariant.copy(alpha = .78f), modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.padding(top = 8.dp)) {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More for ${book.title}",
                    tint = colours.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen(book) })
                DropdownMenuItem(text = { Text("Remove from shelf") }, onClick = {
                    menu = false; onLongPress(book)
                })
            }
        }
    }
}

/** A book's cover, or the mark Moonlight Pages draws in its place when a book has none. */
@Composable
private fun BookCover(book: Book, cover: Bitmap?, modifier: Modifier) {
    val tints = listOf(Color(0xFF3A4A63), Color(0xFF61503D), Color(0xFF45546B), Color(0xFF6A4545))
    val tint = tints[Math.floorMod(book.title.hashCode(), tints.size)]
    Box(modifier.shadow(3.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
        .background(Brush.verticalGradient(listOf(tint, Color(0xFF191E2B)))), contentAlignment = Alignment.Center) {
        if (cover != null) Image(cover.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Text("M P", fontFamily = FontFamily.Serif, color = Color(0xFFFFF3D8), fontSize = 13.sp,
                letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun BookCard(book: Book, store: EpubStore, onOpen: (Book) -> Unit, onLongPress: (Book) -> Unit) {
    val cover = rememberCover(book, store)
    val progress = store.progress(book)
    val finished = store.finished(book)
    // Only the cover carries the rounded, clipped shape. The title and author sit outside it, so no
    // ancestor clip or cover shadow can ever reach the text underneath.
    Column(Modifier.fillMaxWidth()
        .combinedClickable(onClick = { onOpen(book) }, onLongClick = { onLongPress(book) })
        .semantics(mergeDescendants = true) {
            contentDescription = "${book.title}, by ${book.author}" + if (finished) ", finished" else ""
            // Screen readers reach the same action without needing a long press.
            customActions = listOf(CustomAccessibilityAction("Remove this book") { onLongPress(book); true })
        }) {
        val tints = listOf(Color(0xFF3A4A63), Color(0xFF61503D), Color(0xFF45546B), Color(0xFF6A4545))
        val tint = tints[Math.floorMod(book.title.hashCode(), tints.size)]
        Box(Modifier.fillMaxWidth().aspectRatio(.68f).shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(tint, Color(0xFF191E2B)))),
            contentAlignment = Alignment.Center) {
            if (cover != null) Image(cover.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MOONLIGHT PAGES", color = FinishedInk, fontSize = 9.sp, letterSpacing = 1.5.sp)
                HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Color.White.copy(alpha = .25f))
                Text(book.title, fontFamily = FontFamily.Serif, color = Color(0xFFFFF3D8), fontSize = 22.sp,
                    maxLines = 5, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            // A hairline along the foot of the cover shows how far in the reader is.
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                .background(Color.Black.copy(alpha = .34f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(FinishedInk))
            }
            if (finished) Surface(color = Color(0xEB1A2030), shape = RoundedCornerShape(9.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                        tint = FinishedInk, modifier = Modifier.size(13.dp))
                    Text("Finished", color = FinishedInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        // Room for a long title to wrap instead of being cut off after two lines.
        Text(book.title, Modifier.padding(top = 14.dp), fontWeight = FontWeight.SemiBold,
            maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(book.author, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

/** The moonlight colour, which stays legible on every cover and paper. */
private val FinishedInk = Color(0xFFE8C68A)
