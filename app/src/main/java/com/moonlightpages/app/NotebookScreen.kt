package com.moonlightpages.app

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One note, with the book and the words it was written against. */
internal data class NotebookEntry(val book: Book, val mark: BookHighlight)

/**
 * Every note as one plain Markdown file, grouped by the book it came from. Markdown rather than a
 * private format because a note is the reader's own writing: it should outlive this app.
 */
internal fun notebookMarkdown(entries: List<NotebookEntry>, written: (BookHighlight) -> String): String =
    buildString {
        appendLine("# Notebook")
        entries.groupBy { it.book.id }.forEach { (_, group) ->
            val book = group.first().book
            appendLine()
            appendLine("## ${book.title} — ${book.author}")
            group.forEach { entry ->
                val mark = entry.mark
                appendLine()
                appendLine("### ${passageChapter(mark.passage)}")
                appendLine()
                appendLine("> ${mark.passage.text.replace("\n", " ").trim()}")
                appendLine()
                appendLine(mark.note)
                appendLine()
                appendLine("*${written(mark)}*")
            }
        }
    }.trimEnd() + "\n"

/**
 * Everything the reader has written down, across every book on the shelf, newest first. A note is
 * only worth keeping if it can be found again, so each row says which book it came from and what
 * was marked; a note can be rewritten or dropped here without opening the book, and the whole
 * notebook can be written out as a Markdown file.
 */
@Composable
internal fun NotebookScreen(state: LibraryState, store: EpubStore, onOpen: (Book, SelectedPassage) -> Unit) {
    val colours = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<NotebookEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Bumped after a note is changed, so the list is read again from where it is kept.
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<NotebookEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<NotebookEntry?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val now = remember(revision) { System.currentTimeMillis() }

    LaunchedEffect(state.books, revision) {
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

    // The notebook is written out to a file the reader chooses, and nowhere else.
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) {
            val text = notebookMarkdown(entries) { dateLabel(it.createdAt) }
            scope.launch {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                            ?: error("That file could not be written.")
                    }
                }
                message = if (written.isSuccess) "Notebook saved" to
                    "Your notes were written into the file you chose."
                else "Couldn’t save your notes" to
                    (written.exceptionOrNull()?.message ?: "The file couldn’t be written. Nothing has changed.")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 26.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Notebook", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            if (entries.isNotEmpty()) IconButton(onClick = { exporter.launch(NOTEBOOK_FILE) }) {
                Icon(painterResource(R.drawable.ic_export), contentDescription = "Save the notebook as a file",
                    tint = colours.primary)
            }
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
                        NoteRow(entry, now, onOpen = onOpen, onEdit = { editing = it },
                            onDelete = { confirmDelete = it })
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        HighlightEditor(entry.mark.passage, entry.mark, busy, null,
            onSave = { colour, note ->
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { store.highlights.update(entry.book, entry.mark.id, colour, note) }
                    }
                    busy = false
                    editing = null
                    revision++
                }
            },
            onDelete = {
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { store.highlights.remove(entry.book, entry.mark.id) }
                    }
                    busy = false
                    editing = null
                    revision++
                }
            },
            onDismiss = { if (!busy) editing = null })
    }
    confirmDelete?.let { entry ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this highlight?") },
            text = {
                Text("This removes the note and the highlight itself from “${entry.book.title}”. " +
                    "The words you wrote are not kept anywhere else.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { store.highlights.remove(entry.book, entry.mark.id) }
                        }
                        busy = false
                        revision++
                    }
                }) { Text("Delete", color = colours.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep it") } })
    }
    message?.let { (title, body) ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text(title) }, text = { Text(body) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }
}

/** The name the notebook is offered under in the file picker; the reader may change it. */
private const val NOTEBOOK_FILE = "Notebook.md"

private fun dateLabel(at: Long): String =
    SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(at))

@Composable
private fun NoteRow(
    entry: NotebookEntry, now: Long, onOpen: (Book, SelectedPassage) -> Unit,
    onEdit: (NotebookEntry) -> Unit, onDelete: (NotebookEntry) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val mark = entry.mark
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).clickable { onOpen(entry.book, mark.passage) }
            .semantics(mergeDescendants = true) {
                contentDescription = "Note in ${entry.book.title}: ${mark.note}. Open at the marked page"
            }
            .padding(start = 20.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)) {
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
        Box(Modifier.padding(top = 6.dp)) {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_more_vert),
                    contentDescription = "More for this note",
                    tint = colours.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open the page") },
                    onClick = { menu = false; onOpen(entry.book, mark.passage) })
                DropdownMenuItem(text = { Text("Edit note") },
                    onClick = { menu = false; onEdit(entry) })
                DropdownMenuItem(text = { Text("Delete highlight") },
                    onClick = { menu = false; onDelete(entry) })
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colours.outlineVariant.copy(alpha = .5f))
}

@Composable
private fun EmptyNotebook() {
    val colours = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Nothing noted yet.", style = MaterialTheme.typography.headlineSmall)
        Text("A highlight becomes a note when you write on it while reading.\n" +
            "Every note you write gathers here, whichever book it came from.",
            color = colours.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp))
    }
}
