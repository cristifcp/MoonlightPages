package com.moonlightpages.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun matchingBooks(books: List<Book>, query: String): List<Book> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return books.filter { book -> words.all { "${book.title} ${book.author}".contains(it, ignoreCase = true) } }
}

@Composable
internal fun LibraryScreen(
    state: LibraryState, store: EpubStore, onImport: () -> Unit,
    onSample: () -> Unit, onRetry: () -> Unit, onOpen: (Book) -> Unit,
    onSettings: () -> Unit = {}, onDelete: (Book) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(state.books, query) { matchingBooks(state.books, query) }
    val resume = remember(state.books) { store.resumeBook(state.books) }
    val doomed = pendingDelete?.let { id -> state.books.find { it.id == id } }
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Scaffold(modifier = Modifier.imePadding(), floatingActionButton = {
        if (state.books.isNotEmpty() && state.available) ExtendedFloatingActionButton(
            onClick = { if (!state.busy) { keyboard?.hide(); onImport() } },
            modifier = Modifier.semantics { if (state.busy) disabled() },
            containerColor = if (state.busy) colors.surfaceVariant else colors.primary,
            contentColor = if (state.busy) colors.onSurfaceVariant else colors.onPrimary,
        ) {
            if (state.importing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
            }
            Text(if (state.importing) "Importing…" else "+  Import a book")
        }
    }) { padding ->
        // The heading and search scroll with the shelf instead of consuming all of a short screen.
        LazyVerticalGrid(columns = GridCells.Adaptive(136.dp), modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // The reader's own mark, the picture the icon is made from.
                        Image(painterResource(R.drawable.reader_mark), contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                        Column(Modifier.weight(1f)) {
                            Text("Moonlight Pages", style = MaterialTheme.typography.titleLarge)
                            Text("A little space for your next chapter.", style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                        IconButton(onClick = onSettings) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings",
                                tint = colors.onSurfaceVariant)
                        }
                    }
                    Text("Your library", style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(top = 24.dp).semantics { heading() })
                }
            }
            if (state.loading || state.importing) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.importing) "Bringing your book onto the shelf…" else "Opening your library…",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (!state.loading && !state.available) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Let’s try that again.", style = MaterialTheme.typography.headlineSmall)
                    Text("The library couldn’t be loaded. Nothing has been overwritten.", color = colors.onSurfaceVariant)
                    OutlinedButton(onClick = onRetry) { Text("Try again") }
                }
            }
            if (state.books.isEmpty() && !state.loading && state.available) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Good stories start here.", style = MaterialTheme.typography.headlineSmall)
                        Text("Bring a DRM-free EPUB from your phone.\nYour books stay on this device.",
                            textAlign = TextAlign.Center, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 22.dp))
                        Button(onClick = onImport, enabled = !state.busy) { Text("+  Import your first book") }
                        // Names the book that ships in the app's assets (see EpubStore.BUNDLED_BOOK).
                        TextButton(onClick = onSample, enabled = !state.busy) { Text("Or start with Moby Dick") }
                        Text("Offline by design · No account required", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 28.dp))
                    }
                }
            }
            if (state.books.isNotEmpty()) {
                val next = resume
                if (next != null && query.isBlank()) item(key = "resume", span = { GridItemSpan(maxLineSpan) }) {
                    ContinueReadingCard(next, store) { keyboard?.hide(); focus.clearFocus(); onOpen(it) }
                }
                item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                        label = { Text("Search your library") }, placeholder = { Text("Title or author") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search")
                            }
                        }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }))
                }
                item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                    val label = if (query.isBlank()) "${state.books.size} ${if (state.books.size == 1) "book" else "books"} on your shelf"
                    else "${filtered.size} ${if (filtered.size == 1) "match" else "matches"} in your library"
                    Text(if (query.isBlank()) "$label · hold a cover to remove a book" else label,
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                if (filtered.isEmpty()) item(key = "no-results", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text("No books match your search.", style = MaterialTheme.typography.titleMedium)
                        Text("Try a title, an author, or fewer words.", color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = { query = "" }) { Text("Show all books") }
                    }
                }
                items(filtered, key = { it.id }) { book ->
                    BookCard(book, store,
                        onOpen = { keyboard?.hide(); focus.clearFocus(); onOpen(it) },
                        onLongPress = { pendingDelete = it.id })
                }
            }
        }
    }
    doomed?.let { book ->
        DeleteBookDialog(book, onDelete = { pendingDelete = null; onDelete(it) }, onKeep = { pendingDelete = null })
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
private fun BookCard(book: Book, store: EpubStore, onOpen: (Book) -> Unit, onLongPress: (Book) -> Unit) {
    val file = remember(book.id, book.cover) { book.cover?.let { bookFile(store.folder(book), it) } }
    val cover by produceState(file?.let(Covers::cached), file?.path) {
        if (value == null && file != null) value = withContext(Dispatchers.IO) { runCatching { Covers.load(file) }.getOrNull() }
    }
    val progress = store.progress(book)
    val finished = store.finished(book)
    val tints = listOf(Color(0xFF3A4A63), Color(0xFF61503D), Color(0xFF45546B), Color(0xFF6A4545))
    val tint = tints[Math.floorMod(book.title.hashCode(), tints.size)]
    // Only the cover carries the rounded, clipped shape. The title and author sit outside it, so no
    // ancestor clip or cover shadow can ever reach the text underneath.
    Column(Modifier.fillMaxWidth()
        .combinedClickable(onClick = { onOpen(book) }, onLongClick = { onLongPress(book) })
        .semantics(mergeDescendants = true) {
            contentDescription = "${book.title}, by ${book.author}" + if (finished) ", finished" else ""
            // Screen readers reach the same action without needing a long press.
            customActions = listOf(CustomAccessibilityAction("Remove this book") { onLongPress(book); true })
        }) {
        Box(Modifier.fillMaxWidth().aspectRatio(.68f).shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(tint, Color(0xFF191E2B)))),
            contentAlignment = Alignment.Center) {
            if (cover != null) Image(cover!!.asImageBitmap(), contentDescription = null,
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

/**
 * The way back into the book that was open last. It names the book and how far in the reader is,
 * so picking the phone up again takes one tap.
 */
@Composable
private fun ContinueReadingCard(book: Book, store: EpubStore, onOpen: (Book) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val progress = store.progress(book)
    val percent = store.progressPercent(book)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.primaryContainer)
        .clickable { onOpen(book) }
        .semantics(mergeDescendants = true) {
            contentDescription = "Continue reading ${book.title}" +
                if (percent > 0) " at $percent%" else ""
        }
        .padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Continue reading", style = MaterialTheme.typography.labelMedium,
                    color = colors.onPrimaryContainer.copy(alpha = .8f))
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = colors.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            if (percent > 0) Text("$percent%", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = colors.onPrimaryContainer)
        }
        if (progress > 0f) Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(4.dp)
            .clip(RoundedCornerShape(2.dp)).background(colors.onPrimaryContainer.copy(alpha = .2f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(colors.onPrimaryContainer))
        }
    }
}
