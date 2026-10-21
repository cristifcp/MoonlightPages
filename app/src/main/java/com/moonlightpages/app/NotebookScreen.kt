package com.moonlightpages.app

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One note, with the book and the words it was written against. */
internal data class NotebookEntry(val book: Book, val mark: BookHighlight)

/**
 * Everything the reader has written down, across every book on the shelf, newest first. A note is
 * only worth keeping if it can be found again, so each row says which book it came from and what
 * was marked, and tapping it opens that page.
 */
@Composable
internal fun NotebookScreen(state: LibraryState, store: EpubStore, onOpen: (Book, SelectedPassage) -> Unit) {
    val colours = MaterialTheme.colorScheme
    var entries by remember { mutableStateOf<List<NotebookEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val now = remember { System.currentTimeMillis() }

    LaunchedEffect(state.books) {
        loading = true
        entries = withContext(Dispatchers.IO) {
            state.books.flatMap { book ->
                runCatching { store.highlights.load(book) }.getOrDefault(emptyList())
                    .filter { it.hasNote }
                    .map { NotebookEntry(book, it) }
            }
        }.sortedByDescending { it.mark.createdAt }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 26.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Notebook", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        ShelfSheet(Modifier.weight(1f).padding(start = 14.dp, end = 14.dp, top = 16.dp)) {
            when {
                loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp,
                        color = colours.primary)
                }
                entries.isEmpty() -> EmptyNotebook()
                else -> LazyColumn(Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 132.dp)) {
                    item(key = "notebook-heading") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
                            Text("NOTES", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.8.sp),
                                color = colours.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(if (entries.size == 1) "1 note" else "${entries.size} notes",
                                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = .6.sp),
                                color = colours.onSurfaceVariant)
                        }
                    }
                    items(entries, key = { it.book.id + "/" + it.mark.id }) { entry ->
                        NoteRow(entry, now, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(entry: NotebookEntry, now: Long, onOpen: (Book, SelectedPassage) -> Unit) {
    val colours = MaterialTheme.colorScheme
    val mark = entry.mark
    Column(Modifier.fillMaxWidth().clickable { onOpen(entry.book, mark.passage) }
        .semantics(mergeDescendants = true) {
            contentDescription = "Note in ${entry.book.title}: ${mark.note}. Open at the marked page"
        }
        .padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(entry.book.title.uppercase(), style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 1.2.sp, fontSize = 11.sp), color = colours.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(mark.note, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 7.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
        if (mark.passage.text.isNotBlank()) Text("“${mark.passage.text}”",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = colours.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(passageChapter(mark.passage) + " · " +
            DateUtils.getRelativeTimeSpanString(mark.createdAt, now, DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE),
            style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant.copy(alpha = .75f),
            modifier = Modifier.padding(top = 8.dp))
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colours.outlineVariant.copy(alpha = .5f))
}

@Composable
private fun EmptyNotebook() {
    val colours = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nothing noted yet.", style = MaterialTheme.typography.headlineSmall)
        Text("A highlight becomes a note when you write on it while reading.\n" +
            "Every note you write gathers here, whichever book it came from.",
            color = colours.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp))
    }
}
