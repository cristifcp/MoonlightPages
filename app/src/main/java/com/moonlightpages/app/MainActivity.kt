package com.moonlightpages.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.moonlightpages.app.ui.theme.MoonlightTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: LibraryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return LibraryViewModel(applicationContext) as T
            }
        })[LibraryViewModel::class.java]
        // A book tapped in a file manager arrives with the intent that launched the app. Recreating
        // the Activity, as a rotation does, replays that same intent, so it is only taken once.
        if (savedInstanceState == null) handOverBook()
        setContent {
            // Light or dark is the reader's choice, or the phone's own until they make one. The theme
            // wraps everything, so the shelf, the settings and every dialog change together.
            var darkTheme by remember { mutableStateOf(model.store.darkTheme()) }
            MoonlightTheme(darkTheme = darkTheme) {
                MoonlightPages(model, darkTheme = darkTheme, onDarkTheme = {
                    darkTheme = it
                    model.store.saveDarkTheme(it)
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The app was already open, so this really is a new tap on a file.
        handOverBook()
    }

    private fun handOverBook() {
        intent?.handedOverBook()?.let { model.openIncoming(it) }
    }
}

/** The book a file manager or a share handed to the app, if this intent carries one. */
private fun Intent.handedOverBook(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    else -> null
}

/**
 * Shown when a book handed to the app matches one already on the shelf. Nothing is imported until
 * the reader chooses, so a book's reading progress can never be quietly split in two.
 */
@Composable
internal fun DuplicateBookDialog(
    duplicate: Duplicate,
    onOpenExisting: () -> Unit,
    onAddCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    val book = duplicate.staged
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Already on your shelf") },
        text = {
            Text("“${book.title}” by ${book.author} is already in your library. " +
                "Open your copy, or add this file as a second one.")
        },
        confirmButton = { TextButton(onClick = onOpenExisting) { Text("Open my copy") } },
        dismissButton = { TextButton(onClick = onAddCopy) { Text("Add a second copy") } },
    )
}

@Composable
private fun MoonlightPages(
    model: LibraryViewModel,
    darkTheme: Boolean = false,
    onDarkTheme: (Boolean) -> Unit = {},
) {
    val state by model.state.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(ShelfTab.Library.ordinal) }
    // A note tapped in the notebook opens its book at the marked page, not where the book was left.
    var openAt by remember { mutableStateOf<SelectedPassage?>(null) }
    val screens = rememberSaveableStateHolder()
    val selected = state.books.find { it.id == selectedId }
    val current = ShelfTab.entries[tab.coerceIn(ShelfTab.entries.indices)]

    // A book handed to the app from a file manager opens itself once the shelf knows about it.
    LaunchedEffect(state.openBookId) {
        val opening = state.openBookId
        if (opening != null) {
            tab = ShelfTab.Library.ordinal
            selectedId = opening
            model.consumeOpened()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importUri(uri)
    }
    // A backup is written wherever the reader chooses to keep it, and read back from there.
    val backupSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) model.backupTo(uri) }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.restoreFrom(uri)
    }

    // A book takes the whole screen. The rest of the app shares one bar along its foot.
    if (selected != null) {
        ReaderScreen(selected, model.store, openAt = openAt, onBack = { selectedId = null; openAt = null })
    } else {
        Box(Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            when (current) {
                ShelfTab.Notebook -> NotebookScreen(state, model.store) { book, passage ->
                    openAt = passage
                    selectedId = book.id
                }
                ShelfTab.Library -> screens.SaveableStateProvider("library") {
                    LibraryScreen(state, model.store,
                        onImport = { picker.launch(arrayOf(
                            // Anything the file manager can hand over: these are books, not a file type the
                            // system knows. A file that is neither an EPUB nor a FictionBook is refused
                            // plainly once it is open, which is a better gate than a guessed type.
                            "*/*")) },
                        onSample = { model.importSample() }, onRetry = { model.reload() },
                        onOpen = { openAt = null; selectedId = it.id },
                        onDelete = { model.delete(it) })
                }
                ShelfTab.Settings -> SettingsScreen(model.store,
                    onBack = { tab = ShelfTab.Library.ordinal },
                    backingUp = state.backingUp, restoring = state.restoring,
                    darkTheme = darkTheme, onDarkTheme = onDarkTheme,
                    onBackup = { backupSaver.launch(backupFileName()) },
                    onRestore = { backupPicker.launch(SUPPORTED_BACKUP_TYPES) }, showBack = false)
            }
            Box(Modifier.align(Alignment.BottomCenter)) { ShelfBar(current) { tab = it.ordinal } }
        }
        // The notebook and the settings are rooms off the shelf, so Back returns to it.
        BackHandler(current != ShelfTab.Library) { tab = ShelfTab.Library.ordinal }
    }
    state.duplicate?.let { pending ->
        DuplicateBookDialog(pending, model::openExistingCopy, model::addSecondCopy, model::cancelIncoming)
    }
    state.message?.let { message ->
        AlertDialog(onDismissRequest = model::dismissMessage,
            title = { Text(state.messageTitle ?: "Couldn’t import this book") },
            text = { Text(message) }, confirmButton = {
                TextButton(onClick = { if (state.loadFailure) model.reload() else model.dismissMessage() }) {
                    Text(if (state.loadFailure) "Retry" else "OK")
                }
            })
    }
}

/**
 * The page and its controls. [openAt] is a marked passage the reader asked for from the notebook, so
 * the book opens on that page rather than where it was left; the reading place follows from there.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ReaderScreen(
    book: Book, store: EpubStore, openAt: SelectedPassage? = null, onBack: () -> Unit,
) {
    var chapter by rememberSaveable(book.id) { mutableIntStateOf(openAt?.chapter ?: store.chapter(book)) }
    var position by remember(book.id) { mutableFloatStateOf(openAt?.fraction ?: store.position(book)) }
    var textAnchor by remember(book.id) { mutableStateOf(openAt?.start ?: store.anchor(book)) }
    var pageLocation by remember(book.id) { mutableStateOf(ReadingLocation(position, textAnchor)) }
    var pendingBookmarkUpgrade by remember(book.id) { mutableStateOf<String?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableIntStateOf(store.fontSize()) }
    var paperIndex by remember { mutableIntStateOf(store.paper()) }
    var lineSpacing by remember { mutableIntStateOf(store.lineSpacing()) }
    var pageInset by remember { mutableIntStateOf(store.pageInset()) }
    var pageFont by remember { mutableIntStateOf(store.pageFont()) }
    var nightDim by remember { mutableIntStateOf(store.nightDim()) }
    var swipeToTurn by remember { mutableStateOf(store.swipeToTurn()) }
    var tapToTurn by remember { mutableStateOf(store.tapToTurn()) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var contents by rememberSaveable { mutableStateOf(false) }
    var contentsTab by rememberSaveable { mutableIntStateOf(0) }
    var highlights by remember(book.id) { mutableStateOf<List<BookHighlight>>(emptyList()) }
    var highlightsAvailable by remember(book.id) { mutableStateOf(false) }
    var highlightsLoading by remember(book.id) { mutableStateOf(true) }
    var selectedPassage by remember(book.id) { mutableStateOf<SelectedPassage?>(null) }
    var editingHighlightId by remember(book.id) { mutableStateOf<String?>(null) }
    var highlightBusy by remember { mutableStateOf(false) }
    var annotationMessage by remember { mutableStateOf<String?>(null) }
    // Where the book's own chapters begin inside the file being read, once the page can say.
    var namedChapters by remember(book.id) { mutableStateOf<List<NamedChapter>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var fragment by rememberSaveable(book.id) { mutableStateOf<String?>(null) }
    var generation by remember(book.id) { mutableIntStateOf(0) }
    var readerReady by remember { mutableStateOf(false) }
    var bookmarks by remember(book.id) { mutableStateOf(store.bookmarks(book)) }
    var navigation by remember(book.id) {
        mutableStateOf(book.chapters.indices.map { ChapterLink("Section ${it + 1}", it) })
    }
    var loadingContents by remember(book.id) { mutableStateOf(true) }
    var webView by remember { mutableStateOf<BookWebView?>(null) }
    var search by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember(book.id) { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var searchTruncated by remember { mutableStateOf(false) }
    val bookSearch = remember(book.id, store) { BookSearch(store) }
    // Reading is the page and nothing else: the controls come out when the middle of the page is
    // tapped and stand aside the rest of the time. A screen reader needs them from the start.
    val readerContext = LocalContext.current
    val exploring = remember(readerContext) {
        readerContext.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    var controlsVisible by rememberSaveable(book.id) { mutableStateOf(exploring) }
    // A turn holds the page being left behind while the page replacing it is laid over the top.
    var pageTurn by remember(book.id) { mutableStateOf<PageTurn?>(null) }
    // How far through a turn the reader is, read while drawing the layer so a turn costs no recomposition.
    val turnProgress = remember(book.id) { mutableFloatStateOf(0f) }
    var turnSerial by remember(book.id) { mutableIntStateOf(0) }
    // A turn is asked for, then begins once the screen has handed back the page being left.
    var turnQueued by remember(book.id) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val paper = papers[paperIndex]
    val background = Color(android.graphics.Color.parseColor(paper.background))
    val ink = Color(android.graphics.Color.parseColor(paper.ink))
    val currentBookmark = bookmarkOnPage(bookmarks, chapter, pageLocation)
    // A file can hold several chapters of the book, so the name and the pages left belong to the
    // chapter being read, not to the whole file it happens to live in. It is read when a mark is
    // saved rather than kept, because the page and the book's chapter list both arrive after the
    // reader has been built.
    val chapterBeingRead = { namedChapterAt(namedChapters, pageLocation.page)?.title.orEmpty() }
    val chapterTitle = chapterBeingRead().ifEmpty {
        navigation.firstOrNull { it.chapter == chapter }?.title ?: "Section ${chapter + 1}"
    }
    val remaining = pagesLeftInChapter(namedChapters, pageLocation.page)
        ?: (pageLocation.pages - pageLocation.page - 1).coerceAtLeast(0)
    val hint = when {
        !readerReady -> "Opening chapter…"
        remaining == 0 -> "Last page of this chapter"
        remaining == 1 -> "1 page left in this chapter"
        else -> "$remaining pages left in this chapter"
    }
    val pageLabel = if (!readerReady) "…" else {
        // A file can hold several of the book's chapters, so the count belongs to the chapter being
        // read; the file's own count is the fallback when the book names none of them.
        val span = chapterPageSpan(namedChapters, pageLocation.page, pageLocation.pages - 1)
        if (span == null) "${pageLocation.page + 1} of ${pageLocation.pages}"
        else "${pageLocation.page - span.first + 1} of ${span.second - span.first + 1}"
    }
    val noted = highlights.filter { it.hasNote }

    LaunchedEffect(search, searchQuery) {
        searchFailed = false
        searchTruncated = false
        if (!search || searchQuery.trim().length < 2) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(250) // Let a fast typist finish the word before reading every chapter.
        val links = navigation
        val outcome = withContext(Dispatchers.IO) { runCatching { bookSearch.search(book, searchQuery, links) } }
        searching = false
        outcome.onSuccess { hits -> searchResults = hits; searchTruncated = hits.size >= 150 }
            .onFailure { searchResults = emptyList(); searchFailed = it !is kotlinx.coroutines.CancellationException }
    }

    LaunchedEffect(book.id) {
        navigation = withContext(Dispatchers.IO) { store.contents(book) }
        loadingContents = false
    }

    LaunchedEffect(book.id, store) {
        try {
            highlights = withContext(Dispatchers.IO) { store.highlights.load(book) }
            highlightsAvailable = true
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            annotationMessage = "Saved highlights could not be opened. Your files have not been changed. Reopen the book to try again."
        } finally { highlightsLoading = false }
    }

    fun changeHighlights(change: () -> List<BookHighlight>) {
        if (highlightBusy || !highlightsAvailable) return
        highlightBusy = true
        annotationMessage = null
        scope.launch {
            try {
                highlights = withContext(Dispatchers.IO) { change() }
                selectedPassage = null
                editingHighlightId = null
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                annotationMessage = "The highlight could not be saved. Please try again."
            } finally { highlightBusy = false }
        }
    }

    fun editHighlight(mark: BookHighlight) {
        if (highlightBusy) return
        annotationMessage = null
        editingHighlightId = mark.id
        selectedPassage = mark.passage
    }

    fun goToChapter(next: Int, anchor: String? = null, fraction: Float = 0f, savedAnchor: TextAnchor? = null) {
        webView?.savePosition()
        generation++
        readerReady = false
        namedChapters = emptyList()
        renderError = null
        pendingBookmarkUpgrade = null
        // A chapter is not a page, so nothing of the old one is held over the new one.
        pageTurn = null
        position = fraction
        textAnchor = savedAnchor
        pageLocation = ReadingLocation(fraction, savedAnchor)
        fragment = anchor
        chapter = next
        store.savePosition(book, next, fraction, savedAnchor)
    }

    /**
     * Turns the page the way a book does: the page being left is copied, held and shoved aside,
     * and the page arriving travels in from the far side of the screen over the top of it. The copy
     * is read back off the screen, so it is the page as it is seen, at the size it is seen at; that
     * takes a frame, and the turn proper begins the moment it arrives. A page that cannot be copied
     * simply turns.
     */
    fun turnWithAnimation(direction: Int) {
        val view = webView ?: return
        if (turnQueued || !view.canTurn()) return
        val serial = ++turnSerial
        val gen = generation
        turnQueued = true
        view.copyOfPage { copy ->
            turnQueued = false
            if (serial != turnSerial || gen != generation) return@copyOfPage
            view.performTurn(direction)
            if (copy != null) {
                turnProgress.floatValue = 0f
                pageTurn = PageTurn(copy.asImageBitmap(), direction, serial)
            }
        }
    }

    // One clock turns the page, so the arriving page and the page it covers move on the very same
    // frame. The first frame only takes the arriving page off the screen: giving the reader that
    // frame means the page the turn asked for is drawn before any of it is seen moving.
    LaunchedEffect(pageTurn) {
        val turn = pageTurn ?: return@LaunchedEffect
        val view = webView
        val width = view?.width?.toFloat() ?: 0f
        fun hold(progress: Float) {
            val eased = FastOutSlowInEasing.transform(progress)
            turnProgress.floatValue = eased
            view?.translationX = pageTurnTravel(width, turn.direction, eased)
        }
        hold(0f)
        try {
            withFrameNanos { it }
            val started = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val progress = ((now - started) / 1_000_000f / TURN_MILLIS).coerceIn(0f, 1f)
                hold(progress)
                if (progress >= 1f) break
            }
        } finally {
            // Only the turn that is still the current one puts the page back where it belongs.
            if (pageTurn === turn) view?.translationX = 0f
        }
        pageTurn = null
    }

    fun leave() { webView?.savePosition(); onBack() }
    BackHandler { if (webView?.dismissSelection() != true) leave() }

    DisposableEffect(lifecycleOwner, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) view?.savePosition()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Reading in bed should not need a screen tap every minute.
    val keepAwake = remember { store.keepAwake() }
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, keepAwake) {
        if (keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Night dimming takes the phone's own brightness down rather than laying anything over the
    // screen. A film over the page would be caught in the very picture a page turn holds of it: the
    // page being left would carry the dimming already and then be dimmed again, so it would not
    // match the page arriving beside it. Taking the brightness down also dims the whole screen —
    // page, controls and all — so nothing glares and no contrast is given away. With dimming off the
    // window is not touched at all: the phone's own brightness stays its own business.
    DisposableEffect(window, nightDim) {
        val reading = window?.takeIf { nightDim > 0 } ?: return@DisposableEffect onDispose {}
        val before = reading.attributes.screenBrightness
        val phone = Settings.System.getInt(view.context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(1, 255) / 255f
        val taken = (phone * (1f - nightDim / 100f)).coerceIn(MIN_READING_BRIGHTNESS, 1f)
        reading.attributes = reading.attributes.apply { screenBrightness = taken }
        onDispose { reading.attributes = reading.attributes.apply { screenBrightness = before } }
    }

    MoonlightTheme(darkTheme = paperIndex == 2, paper = background) {
    Box(Modifier.fillMaxSize().background(background)) {
        // The page spans the screen and the controls float over it. The page is inset so that no
        // line of text is ever hidden behind the hint, the page number, or the toolbar.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
            .padding(top = 104.dp, bottom = 96.dp)) {
            Box(Modifier.fillMaxSize().clipToBounds().background(background)) {
            key(book.id, generation) {
                val renderedGeneration = generation
                val renderedChapter = chapter
                val initial = position
                val links = navigation.filter { it.chapter == renderedChapter }
                AndroidView(modifier = Modifier.fillMaxSize().clipToBounds().background(background), factory = { context ->
                    readerReady = false
                    BookWebView(context, store.folder(book), book, renderedChapter,
                        fontSize, paper, initial,
                        spacing = lineSpacing, inset = pageInset, font = pageFont,
                        onPosition = { location ->
                            // Ignore a departing chapter's last callback after navigation.
                            if (generation == renderedGeneration) {
                                position = location.fraction
                                textAnchor = location.anchor
                                pageLocation = location
                                store.savePosition(book, renderedChapter, location.fraction, location.anchor)
                                val upgradeId = pendingBookmarkUpgrade
                                if (upgradeId != null && location.anchor != null) {
                                    bookmarks = store.anchorBookmark(book, upgradeId, location)
                                    pendingBookmarkUpgrade = null
                                }
                            }
                        }, onChapter = { next, anchor -> goToChapter(next, anchor) },
                        fragment = fragment, initialAnchor = textAnchor,
                        onBoundary = { direction ->
                            val next = renderedChapter + direction
                            if (next in book.chapters.indices) goToChapter(next, fraction = if (direction < 0) 1f else 0f)
                        }, onError = { message ->
                            if (generation == renderedGeneration) { renderError = message; readerReady = false }
                        }, onReady = {
                            if (generation == renderedGeneration) {
                                fragment = null
                                readerReady = true
                            }
                        }, initialHighlights = highlights,
                        onSelection = { passage ->
                            if (generation == renderedGeneration && !highlightBusy) {
                                if (highlightsAvailable) {
                                    editingHighlightId = null
                                    annotationMessage = null
                                    // The mark keeps the name the book gives this chapter.
                                    selectedPassage = passage.copy(title = chapterBeingRead())
                                } else annotationMessage = "Highlights are not available yet. Please reopen this book if the problem continues."
                            }
                        }, onHighlightTapped = { id ->
                            if (generation == renderedGeneration) highlights.find { it.id == id }?.let { editHighlight(it) }
                        }, onAnnotationError = { annotationMessage = it },
                            namedChapters = links,
                            onNamedChapters = { placed ->
                                if (generation == renderedGeneration) namedChapters = placed
                            }).also {
                            it.updateTapToTurn(tapToTurn); it.updateSwipeToTurn(swipeToTurn)
                            it.onTurn = { direction -> turnWithAnimation(direction) }
                            it.onMiddleTap = { controlsVisible = !controlsVisible }
                            webView = it
                        }
                }, update = { it.updateHighlights(highlights); it.updateAppearance(fontSize, paper, lineSpacing, pageInset, pageFont)
                    it.updateNamedChapters(links)
                    it.updateTapToTurn(tapToTurn); it.updateSwipeToTurn(swipeToTurn) }, onRelease = { it.release() })
            }
            // The page being left behind, held over the page itself: drawn inside the page's own
            // bounds, so it lands exactly where that page was without anyone measuring the distance.
            PageTurnCopy(pageTurn, progress = { turnProgress.floatValue }, modifier = Modifier.matchParentSize())
            if (!readerReady && renderError == null) Column(
                Modifier.fillMaxSize().background(background), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(32.dp), color = ink, strokeWidth = 2.dp)
                Text("Opening chapter…", Modifier.padding(top = 16.dp), color = ink.copy(alpha = .7f))
            }
            }
            // A soft edge so the page does not end on a hard cut above the page number.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, background))))
        }
        // The dimming and the shadow reach the screen's own edges, so a turn reads as the page
        // itself rather than as a panel of the screen turning.
        PageTurnShade(pageTurn, progress = { turnProgress.floatValue }, modifier = Modifier.matchParentSize())
        AnimatedVisibility(controlsVisible, enter = fadeIn(tween(CONTROL_FADE_MILLIS)),
            exit = fadeOut(tween(CONTROL_FADE_MILLIS)), modifier = Modifier.align(Alignment.TopCenter)) {
            Box(Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 18.dp)) {
                ReaderHint(hint, Modifier.align(Alignment.TopCenter).padding(top = 22.dp))
                Column(Modifier.align(Alignment.TopEnd).padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReaderCircleButton(R.drawable.ic_close, "Close book") { leave() }
                    ReaderCircleButton(
                        if (currentBookmark == null) R.drawable.ic_bookmark_outline else R.drawable.ic_bookmark_filled,
                        if (currentBookmark == null) "Bookmark this place" else "Remove this bookmark",
                        enabled = readerReady,
                    ) {
                        val active = webView
                        active?.currentLocation()?.let { location ->
                            active.savePosition()
                            val existing = bookmarkOnPage(bookmarks, chapter, location)
                            bookmarks = if (existing == null)
                                store.addBookmark(book, chapter, location.fraction, chapterTitle, location.anchor)
                            else store.removeBookmark(book, existing.id)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(controlsVisible, enter = fadeIn(tween(CONTROL_FADE_MILLIS)),
            exit = fadeOut(tween(CONTROL_FADE_MILLIS)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ReaderHint(pageLabel)
                Spacer(Modifier.height(10.dp))
                ReaderPill(
                    onContents = { webView?.dismissSelection(); settings = false; contentsTab = 0; contents = true },
                    onAppearance = { webView?.dismissSelection(); webView?.savePosition(); contents = false; settings = true },
                    onBookmarks = { webView?.dismissSelection(); settings = false; contentsTab = 1; contents = true },
                    onSearch = { webView?.dismissSelection(); settings = false; contents = false; search = true },
                )
            }
        }
    }
    if (settings) AppearanceSheet(fontSize, paperIndex, lineSpacing, pageInset, pageFont, nightDim,
        onSize = { size -> webView?.savePosition(); fontSize = size
            store.saveAppearance(size, paperIndex, lineSpacing, pageInset, pageFont) },
        onPaper = { index -> webView?.savePosition(); paperIndex = index
            store.saveAppearance(fontSize, index, lineSpacing, pageInset, pageFont) },
        onSpacing = { index -> webView?.savePosition(); lineSpacing = index
            store.saveAppearance(fontSize, paperIndex, index, pageInset, pageFont) },
        onInset = { index -> webView?.savePosition(); pageInset = index
            store.saveAppearance(fontSize, paperIndex, lineSpacing, index, pageFont) },
        onFont = { index -> webView?.savePosition(); pageFont = index
            store.saveAppearance(fontSize, paperIndex, lineSpacing, pageInset, index) },
        onDim = { percent -> nightDim = percent; store.saveNightDim(percent) },
        onDismiss = { settings = false })
    if (contents) ModalBottomSheet(onDismissRequest = { contents = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(book.title, fontFamily = FontFamily.Serif, fontSize = 24.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                FilterChip(selected = contentsTab == 0, onClick = { contentsTab = 0 }, label = { Text("Chapters") })
                FilterChip(selected = contentsTab == 1, onClick = { contentsTab = 1 },
                    label = { Text("Bookmarks (${bookmarks.size})") })
                FilterChip(selected = contentsTab == 2, onClick = { contentsTab = 2 },
                    label = { Text("Highlights (${highlights.size})") })
                FilterChip(selected = contentsTab == 3, onClick = { contentsTab = 3 },
                    label = { Text("Notebook (${noted.size})") })
            }
            if (contentsTab == 1 && bookmarks.isEmpty()) {
                Text("Keep a place worth returning to.\nTap the bookmark icon while reading.",
                    modifier = Modifier.padding(top = 16.dp, bottom = 40.dp))
            } else if (contentsTab == 2 && highlightsAvailable && highlights.isEmpty()) {
                Text("Long-press a word, adjust the selection handles, then choose Highlight.",
                    modifier = Modifier.padding(top = 16.dp, bottom = 40.dp))
            } else if (contentsTab == 3 && highlightsAvailable && noted.isEmpty()) {
                Text("Your notes gather here.\nTap a highlight while reading, then write what it made you think.",
                    modifier = Modifier.padding(top = 16.dp, bottom = 40.dp))
            } else if ((contentsTab == 2 || contentsTab == 3) && !highlightsLoading && !highlightsAvailable) {
                Text("Highlights couldn’t be loaded. Reopen the book to try again.",
                    modifier = Modifier.padding(top = 16.dp, bottom = 40.dp))
            } else if ((contentsTab == 0 && loadingContents) || ((contentsTab == 2 || contentsTab == 3) && highlightsLoading)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 24.dp))
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (contentsTab == 1) {
                        items(bookmarks.size, key = { bookmarks[it].id }) { index ->
                            val mark = bookmarks[index]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    goToChapter(mark.chapter, fraction = mark.position, savedAnchor = mark.anchor)
                                    if (mark.anchor == null) pendingBookmarkUpgrade = mark.id
                                    contents = false
                                }, modifier = Modifier.weight(1f).semantics {
                                    contentDescription = "Open bookmark: ${mark.title}"
                                }) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(mark.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${(mark.position * 100).toInt()}% through",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                IconButton(onClick = { bookmarks = store.removeBookmark(book, mark.id) }) {
                                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete bookmark: ${mark.title}")
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                        }
                    } else if (contentsTab == 2 || contentsTab == 3) {
                        val listed = if (contentsTab == 3) noted else highlights
                        items(listed.size, key = { listed[it].id }) { index ->
                            val mark = listed[index]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    goToChapter(mark.passage.chapter, fraction = mark.passage.fraction, savedAnchor = mark.passage.start)
                                    contents = false
                                }, modifier = Modifier.weight(1f).semantics { contentDescription = "Open highlight: ${mark.passage.text}" }) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(mark.passage.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        if (mark.hasNote && contentsTab == 3) {
                                            Text(mark.note, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("${mark.color.label} · ${passageChapter(mark.passage)}" +
                                            if (mark.hasNote) " · Your note" else "",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                TextButton(onClick = { contents = false; editHighlight(mark) }) {
                                    Text(if (contentsTab == 3) "Edit note" else "Edit")
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                        }
                    } else {
                        items(navigation.size) { index ->
                            val link = navigation[index]
                            TextButton(onClick = {
                                goToChapter(link.chapter, link.fragment); contents = false
                            }, modifier = Modifier.fillMaxWidth().padding(start = (link.depth * 14).dp).semantics {
                                contentDescription = "Go to ${link.title}"
                            }) {
                                Text("${if (chapter == link.chapter) "• " else ""}${link.title}",
                                    modifier = Modifier.fillMaxWidth(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    if (search) SearchSheet(searchQuery, searchResults, searching, searchFailed, searchTruncated,
        chapterTitle = { index -> navigation.firstOrNull { it.chapter == index }?.title ?: "Section ${index + 1}" },
        onQuery = { searchQuery = it },
        onOpen = { hit ->
            search = false
            goToChapter(hit.chapter, fraction = hit.fraction, savedAnchor = hit.anchor)
        }, onDismiss = { search = false })
    selectedPassage?.let { passage ->
        val existing = highlights.find { it.id == editingHighlightId }
        HighlightEditor(passage, existing, highlightBusy, annotationMessage,
            onSave = { color, note ->
                changeHighlights {
                    if (existing == null) store.highlights.add(book, passage, color, note)
                    else store.highlights.update(book, existing.id, color, note)
                }
            }, onDelete = {
                existing?.let { changeHighlights { store.highlights.remove(book, it.id) } }
            }, onDismiss = { selectedPassage = null; editingHighlightId = null; annotationMessage = null })
    }
    if (selectedPassage == null) annotationMessage?.let { message ->
        AlertDialog(onDismissRequest = { annotationMessage = null }, title = { Text("Highlights") },
            text = { Text(message) }, confirmButton = {
                TextButton(onClick = { annotationMessage = null }) { Text("OK") }
            })
    }
    renderError?.let { message ->
        AlertDialog(onDismissRequest = { leave() }, title = { Text("Couldn’t display this page") },
            text = { Text(message) }, confirmButton = {
                TextButton(onClick = { goToChapter(chapter, fraction = position, savedAnchor = textAnchor) }) { Text("Retry") }
            }, dismissButton = { TextButton(onClick = { leave() }) { Text("Library") } })
    }
    }
}

/** The reader needs the host activity only to keep the screen awake while a book is open. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
