package com.moonlightpages.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

internal data class LibraryState(
    val books: List<Book> = emptyList(), val loading: Boolean = true,
    val importing: Boolean = false, val available: Boolean = false,
    val message: String? = null, val messageTitle: String? = null, val loadFailure: Boolean = false,
    /** A book handed to the app from outside, to open as soon as the shelf is ready. */
    val openBookId: String? = null,
    /** An incoming book that matches one already on the shelf, waiting for the reader's choice. */
    val duplicate: Duplicate? = null,
    /** A backup is being written, or one is being put back. */
    val backingUp: Boolean = false, val restoring: Boolean = false,
) {
    /** True while the library is being changed, so one job never runs over another. */
    val busy: Boolean get() = importing || backingUp || restoring
}

/** An incoming book that looks like one already on the shelf, and the copy already there. */
internal data class Duplicate(val staged: Book, val existing: Book)

/** Import jobs and their UI state survive Activity recreation, without retaining an Activity. */
internal class LibraryViewModel(
    context: Context,
    internal val store: EpubStore = EpubStore(context),
    private val openStream: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) },
    /** Where a backup is written; kept apart from the shelf so a test can keep one in memory. */
    private val openOutput: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it) },
    private val backup: ReadingBackup = ReadingBackup(context, store),
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryState())
    val state = mutableState.asStateFlow()

    /** A book handed to the app while the shelf was still getting ready. */
    private var waiting: Uri? = null

    /** Set while a book is being taken off the shelf, so one removal cannot overlap another. */
    private var removing = false

    init { reload() }

    fun reload() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(loading = true, available = false, message = null, loadFailure = false)
        viewModelScope.launch {
            try {
                val books = withContext(Dispatchers.IO) { store.load() }
                mutableState.value = LibraryState(books = books, loading = false, available = true)
                takeWaiting()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(loading = false, available = false,
                    loadFailure = true, messageTitle = "Couldn’t load your library",
                    message = "Your saved library couldn’t be opened. Your book files have not been changed.")
            }
        }
    }

    fun dismissMessage() { mutableState.value = mutableState.value.copy(message = null) }
    fun importSample() = importBook { store.bundledBook() }
    fun importUri(uri: Uri) = importBook {
        val input = openStream(uri) ?: error("This file could not be opened.")
        input.use { store.importBook(it) }
    }

    internal fun importBook(create: () -> Book) {
        if (mutableState.value.busy || !mutableState.value.available) return
        mutableState.value = mutableState.value.copy(importing = true, message = null, loadFailure = false)
        viewModelScope.launch {
            try {
                val books = withContext(Dispatchers.IO) { store.importIntoLibrary(create) }
                mutableState.value = mutableState.value.copy(books = books, importing = false)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(importing = false,
                    messageTitle = "Couldn’t import this book",
                    message = failure.message ?: "This book couldn’t be imported. Please try another DRM-free EPUB.")
            }
        }
    }

    /**
     * Takes a book off the shelf for good. The copy Moonlight Pages made goes with it, along with the
     * reading position, bookmarks and highlights saved for it. The file it was imported from is
     * never touched.
     */
    fun delete(book: Book) {
        if (removing || mutableState.value.busy) return
        removing = true
        mutableState.value = mutableState.value.copy(message = null, messageTitle = null)
        viewModelScope.launch {
            try {
                val books = withContext(Dispatchers.IO) { store.removeFromLibrary(book) }
                mutableState.value = mutableState.value.copy(books = books)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    messageTitle = "Couldn’t remove this book",
                    message = failure.message ?: "This book couldn’t be removed.")
            } finally { removing = false }
        }
    }

    /**
     * Writes everything the reader has made — their books, reading places, bookmarks, highlights and
     * notes — into a file they chose to keep it in, so it survives a phone being lost or replaced.
     */
    fun backupTo(uri: Uri) {
        if (mutableState.value.busy || !mutableState.value.available) return
        mutableState.value = mutableState.value.copy(backingUp = true, message = null, messageTitle = null)
        viewModelScope.launch {
            try {
                val tally = withContext(Dispatchers.IO) {
                    val output = openOutput(uri) ?: error("That file could not be written.")
                    output.use { backup.save(it) }
                }
                mutableState.value = mutableState.value.copy(backingUp = false,
                    messageTitle = "Backup saved",
                    message = "Saved ${tally.describe()} into the file you chose. Keep it somewhere other " +
                        "than this phone, such as a computer or a cloud drive.")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(backingUp = false,
                    messageTitle = "Couldn’t save your backup",
                    message = failure.message ?: "The backup couldn’t be written. Nothing has been changed.")
            }
        }
    }

    /**
     * Adds everything a backup holds, without removing anything already here: a book already on the
     * shelf stays exactly as it is, a reading place only moves when the backup is further along, and a
     * note written since the backup was made is never taken back.
     */
    fun restoreFrom(uri: Uri) {
        if (mutableState.value.busy || !mutableState.value.available) return
        mutableState.value = mutableState.value.copy(restoring = true, message = null, messageTitle = null)
        viewModelScope.launch {
            try {
                val tally = withContext(Dispatchers.IO) {
                    val input = openStream(uri) ?: error("That file could not be opened.")
                    input.use { backup.restore(it) }
                }
                val books = withContext(Dispatchers.IO) { store.load() }
                mutableState.value = mutableState.value.copy(books = books, restoring = false,
                    messageTitle = if (tally.nothing) "Already on this phone" else "Backup restored",
                    message = if (tally.nothing) "Everything in this backup is already on your phone."
                    else "Brought back ${tally.describe()}. Anything already here was kept.")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(restoring = false,
                    messageTitle = "Couldn’t restore this backup",
                    message = failure.message ?: "This file couldn’t be read. Nothing has been changed.")
            }
        }
    }

    /**
     * Takes a book handed to the app from outside — "Open with Moonlight Pages" from a file manager, or a
     * share. The file is unpacked first, then weighed against the shelf: the very same file opens the
     * copy already there, a lookalike asks the reader, and anything genuinely new is simply added.
     */
    fun openIncoming(uri: Uri) {
        if (!mutableState.value.available || mutableState.value.busy) { waiting = uri; return }
        mutableState.value = mutableState.value.copy(importing = true, message = null, loadFailure = false)
        viewModelScope.launch {
            var staged: Book? = null
            try {
                val (books, known) = withContext(Dispatchers.IO) { store.load() to store.knownFiles() }
                staged = withContext(Dispatchers.IO) {
                    val input = openStream(uri) ?: error("This file could not be opened.")
                    input.use { store.importBook(it) }
                }
                when (val incoming = decide(staged, books, known)) {
                    is Incoming.Same -> {
                        discard(staged)
                        mutableState.value = mutableState.value.copy(
                            importing = false, openBookId = incoming.book.id)
                    }
                    is Incoming.Ask -> mutableState.value = mutableState.value.copy(
                        importing = false, duplicate = Duplicate(staged, incoming.existing))
                    Incoming.Add -> {
                        val updated = withContext(Dispatchers.IO) { store.addToLibrary(staged) }
                        mutableState.value = mutableState.value.copy(
                            books = updated, importing = false, openBookId = staged.id)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // A file that could not be read must leave the shelf exactly as it was.
                staged?.let { unreadable -> discard(unreadable) }
                mutableState.value = mutableState.value.copy(importing = false,
                    messageTitle = "Couldn’t import this book",
                    message = failure.message ?: "This book couldn’t be opened.")
            }
            takeWaiting()
        }
    }

    /** Keeps the incoming file as a second copy of a book that is already on the shelf. */
    fun addSecondCopy() {
        val pending = mutableState.value.duplicate ?: return
        mutableState.value = mutableState.value.copy(duplicate = null, importing = true)
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { store.addToLibrary(pending.staged) }
                mutableState.value = mutableState.value.copy(
                    books = updated, importing = false, openBookId = pending.staged.id)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                discard(pending.staged)
                mutableState.value = mutableState.value.copy(importing = false,
                    messageTitle = "Couldn’t add this book",
                    message = failure.message ?: "This book couldn’t be added.")
            }
            takeWaiting()
        }
    }

    /** Opens the copy already on the shelf and drops the incoming file. */
    fun openExistingCopy() {
        val pending = mutableState.value.duplicate ?: return
        mutableState.value = mutableState.value.copy(duplicate = null, openBookId = pending.existing.id)
        viewModelScope.launch {
            // Remember the answer, so this file opens straight into the same book from now on.
            withContext(Dispatchers.IO) {
                runCatching { store.rememberCopy(pending.existing, pending.staged.fingerprint) }
            }
            discard(pending.staged)
            takeWaiting()
        }
    }

    /** Leaves the shelf untouched and drops the incoming file. */
    fun cancelIncoming() {
        val pending = mutableState.value.duplicate ?: return
        mutableState.value = mutableState.value.copy(duplicate = null)
        viewModelScope.launch { discard(pending.staged); takeWaiting() }
    }

    /** Marks the book handed to the app as opened, so it is not opened twice. */
    fun consumeOpened() { mutableState.value = mutableState.value.copy(openBookId = null) }

    private suspend fun discard(book: Book) {
        withContext(Dispatchers.IO) { runCatching { store.discard(book) } }
    }

    private fun takeWaiting() {
        waiting?.let { uri -> waiting = null; openIncoming(uri) }
    }
}
