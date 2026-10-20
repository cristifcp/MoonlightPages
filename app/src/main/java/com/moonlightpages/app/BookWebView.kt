package com.moonlightpages.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File
import java.net.URLConnection
import kotlin.math.abs

internal data class Paper(val name: String, val background: String, val ink: String)

internal val papers = listOf(
    Paper("White", "#FFFEFA", "#282E29"),
    Paper("Sepia", "#F3EADB", "#3F352A"),
    Paper("Night", "#141A26", "#E4E8EF"),
)

private data class ReaderAppearance(
    val size: Int, val paper: Paper,
    val spacing: Int = ReadingTypography.defaultSpacing,
    val inset: Int = ReadingTypography.defaultInset,
    val font: Int = ReadingTypography.defaultFont,
) {
    fun json() = JSONObject().put("fontSize", size)
        .put("background", paper.background).put("ink", paper.ink)
        .put("lineHeight", ReadingTypography.spacing(spacing) / 100.0)
        .put("inset", ReadingTypography.inset(inset))
        .put("fontFamily", ReadingTypography.font(font))
}

/** Only bundled pagination code runs. EPUB scripts, network, and native JS bridges are blocked. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class BookWebView(
    context: Context,
    private val folder: File,
    private val book: Book,
    private val chapter: Int,
    fontSize: Int,
    paper: Paper,
    private val initialPosition: Float,
    private val onPosition: (ReadingLocation) -> Unit,
    private val onChapter: (Int, String?) -> Unit,
    private val fragment: String? = null,
    private val initialAnchor: TextAnchor? = null,
    private val onBoundary: (Int) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onError: (String) -> Unit = {},
    initialHighlights: List<BookHighlight> = emptyList(),
    private val onSelection: (SelectedPassage) -> Unit = {},
    private val onHighlightTapped: (String) -> Unit = {},
    private val onAnnotationError: (String) -> Unit = {},
    /** The book's own chapters inside this file, so the reader can name the one being read. */
    namedChapters: List<ChapterLink> = emptyList(),
    private val onNamedChapters: (List<NamedChapter>) -> Unit = {},
    /** How the page is set: line spacing, room at the edges, and the face it is printed in. */
    spacing: Int = ReadingTypography.defaultSpacing,
    inset: Int = ReadingTypography.defaultInset,
    font: Int = ReadingTypography.defaultFont,
) : WebView(context) {
    private var ready = false
    private var released = false
    private var pending = false
    private var initializing = false
    private var checks = 0
    private var location = ReadingLocation(initialPosition, initialAnchor)
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    /** A swipe being read: which way it is going, how far it has gone, and how fast it was let go. */
    private var swipeDirection = 0
    private var swipeOffset = 0f
    private var swipeTracker: VelocityTracker? = null
    private var tapToTurn = true
    private var swipeToTurn = true
    private var selectionMode: ActionMode? = null
    private var chapterHighlights = initialHighlights.filter { it.passage.chapter == chapter }
    /** Only anchors this file can actually be asked about; a chapter without one cannot be placed. */
    private var chapterLinks = namedChapters.filterNot { it.fragment.isNullOrEmpty() }
    /** The reader may take a turn over, so it can hold the page being left behind while it animates. */
    var onTurn: ((Int) -> Unit)? = null
    /** A tap that is neither an edge nor a highlight: where the reader shows and hides its controls. */
    var onMiddleTap: (() -> Unit)? = null
    /** One page-sized copy, reused by every turn so that turning a page allocates nothing. */
    private var snapshot: Bitmap? = null
    /** How many times the screen has handed back a copy of the page: a turn's copy is one more. */
    @Volatile var copies = 0
        private set
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var desiredAppearance =
        ReaderAppearance(fontSize, paper, spacing, inset, font)
    private var appliedAppearance: ReaderAppearance? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val poll = Runnable { checkReady() }
    private val reflow = Runnable { if (ready && !released) execute("relayout()", repaginated = true) }

    init {
        setBackgroundColor(Color.parseColor(paper.background))
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isNestedScrollingEnabled = false
        // Keep WebView's image/compositor layers inside the page, away from Compose chrome.
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
        settings.apply {
            // evaluateJavascript runs app-owned code; CSP disallows all document scripts.
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            defaultTextEncodingName = "UTF-8"
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (uri.scheme != "https" || uri.host != HOST) return missing()
                return try {
                    val file = bookFile(folder, uri.path.orEmpty().removePrefix("/"))
                    if (!file.isFile) return missing()
                    val html = file.extension.lowercase() in listOf("html", "xhtml", "htm")
                    val mime = if (html) "text/html" else when (file.extension.lowercase()) {
                        "css" -> "text/css"
                        "svg" -> "image/svg+xml"
                        "woff" -> "font/woff"
                        "woff2" -> "font/woff2"
                        "otf" -> "font/otf"
                        "ttf" -> "font/ttf"
                        else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                    }
                    val style = desiredAppearance
                    val stream = if (html) ChapterHtml.prepare(file.readText(), style.size, style.paper,
                        style.spacing, style.inset, style.font).byteInputStream()
                        else file.inputStream()
                    WebResourceResponse(mime, if (html || mime == "text/css") "UTF-8" else null,
                        200, "OK", mapOf(
                            "Content-Security-Policy" to "default-src 'none'; script-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; base-uri 'none'; form-action 'none'; frame-src 'none'; connect-src 'none'",
                            "X-Content-Type-Options" to "nosniff",
                        ), stream)
                } catch (_: Exception) { missing() }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "https" || uri.host != HOST) return true
                val next = book.chapters.indexOf(uri.path.orEmpty().removePrefix("/"))
                if (next >= 0 && next != chapter) {
                    savePosition()
                    onChapter(next, uri.encodedFragment)
                } else if (next == chapter && uri.encodedFragment != null && ready) {
                    execute("fragment(${JSONObject.quote(uri.encodedFragment)})")
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (initializing || released) return
                initializing = true
                val target = JSONObject().put("fraction", initialPosition.toDouble())
                    .put("anchor", writeAnchor(initialAnchor)).put("fragment", fragment)
                    .put("highlights", JSONArray(chapterHighlights.map { highlightJson(it) }))
                    .put("appearance", desiredAppearance.json())
                val script = context.assets.open("paginated-reader.js").bufferedReader().use { it.readText() }
                evaluateJavascript("$script\nwindow.__moonlight.initialize($target);") {
                    if (!released) checkReady()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !released) onError("This chapter could not be displayed.")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && !released) onError("This chapter file could not be opened.")
            }
        }
        val path = book.chapters[chapter].split('/').joinToString("/") { Uri.encode(it) }
        loadUrl("https://$HOST/$path")
    }

    private fun checkReady() {
        if (released) return
        evaluateJavascript("window.__moonlight && window.__moonlight.ready ? JSON.stringify(window.__moonlight.snapshot()) : null") { result ->
            if (released) return@evaluateJavascript
            val json = decode(result)
            if (json == null) {
                if (++checks < 200) postDelayed(poll, 50)
                else onError("This chapter could not be laid out. Try another chapter or book.")
            } else {
                ready = true
                accept(json)
                applyAppearanceIfNeeded()
                annotationQuery("setHighlights(${JSONArray(chapterHighlights.map { highlightJson(it) })})") {}
                placeNamedChapters()
                selectionMode?.invalidate()
                onReady()
            }
        }
    }

    private fun decode(value: String): JSONObject? = runCatching {
        val text = JSONTokener(value).nextValue() as? String ?: return@runCatching null
        JSONObject(text)
    }.getOrNull()

    private fun decodeArray(value: String): JSONArray? = runCatching {
        val text = JSONTokener(value).nextValue() as? String ?: return@runCatching null
        JSONArray(text)
    }.getOrNull()

    private fun accept(json: JSONObject) {
        val pages = json.optInt("pages", 1).coerceAtLeast(1)
        location = ReadingLocation(json.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f),
            readAnchor(json.optJSONObject("anchor")), readAnchor(json.optJSONObject("start")),
            readAnchor(json.optJSONObject("end")), json.optInt("page", 0).coerceIn(0, pages - 1), pages)
        onPosition(location)
    }

    private fun execute(command: String, repaginated: Boolean = false) {
        if (!ready || released || pending) return
        pending = true
        evaluateJavascript("JSON.stringify(window.__moonlight.$command)") { value ->
            pending = false
            if (!released) {
                val json = decode(value)
                if (json == null) onError("The page could not be updated. Please reopen this book.")
                else if (json.has("boundary")) { savePosition(); onBoundary(json.getInt("boundary")) }
                else { accept(json); if (repaginated) placeNamedChapters() }
                applyAppearanceIfNeeded()
            }
        }
    }

    /** True when this page is sitting still: a turn can only start from a settled page. */
    fun canTurn(): Boolean = ready && !released && !pending

    /** Turns the page; the new place arrives through onPosition, or onBoundary at a chapter edge. */
    fun performTurn(direction: Int) = execute("turn($direction)")

    /**
     * Asks the screen for a copy of the page as it stands now, so the reader can keep it still
     * underneath the page replacing it, or null when the page cannot be copied or holds nothing
     * worth animating. The screen itself is asked rather than this view asked to draw itself: a
     * WebView drawing into a bitmap lays the page out on its own terms, which are not the terms it
     * is laid out on the screen at, and the copy would come back at another size and another place.
     */
    fun copyOfPage(onCopy: (Bitmap?) -> Unit) {
        if (!ready || released || width <= 0 || height <= 0) return onCopy(null)
        val window = hostingWindow(context) ?: return onCopy(null)
        val at = IntArray(2)
        getLocationInWindow(at)
        val area = Rect(at[0], at[1], at[0] + width, at[1] + height)
        val copy = snapshot?.takeIf { !it.isRecycled && it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { snapshot = it }
        try {
            PixelCopy.request(window, area, copy, { result ->
                val page = copy.takeIf { result == PixelCopy.SUCCESS && hasInk(it) }
                if (page != null) copies++
                onCopy(page)
            }, handler)
        } catch (_: IllegalArgumentException) {
            onCopy(null)
        }
    }

    /** A page of nothing but its own paper has nothing worth animating across the screen. */
    private fun hasInk(page: Bitmap): Boolean {
        val stepX = (page.width / 24).coerceAtLeast(1)
        val stepY = (page.height / 48).coerceAtLeast(1)
        val paper = page.getPixel(0, 0)
        var y = 0
        while (y < page.height) {
            var x = 0
            while (x < page.width) {
                if (page.getPixel(x, y) != paper) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    fun turnPage(direction: Int) {
        if (direction == 1 || direction == -1) {
            selectionMode?.finish()
            val host = onTurn
            if (host != null) host(direction) else execute("turn($direction)")
        }
    }

    /** Both ways of turning a page are preferences, so the values can arrive after the view exists. */
    fun updateTapToTurn(enabled: Boolean) { tapToTurn = enabled }
    fun updateSwipeToTurn(enabled: Boolean) { swipeToTurn = enabled }
    fun currentLocation(): ReadingLocation? = if (ready && !released) location else null
    fun savePosition() { currentLocation()?.let(onPosition) }

    fun updateAppearance(
        size: Int, paper: Paper,
        spacing: Int = ReadingTypography.defaultSpacing,
        inset: Int = ReadingTypography.defaultInset,
        font: Int = ReadingTypography.defaultFont,
    ) {
        val next = ReaderAppearance(size.coerceIn(14, 32), paper, spacing, inset, font)
        if (desiredAppearance == next) return
        dismissSelection()
        desiredAppearance = next
        setBackgroundColor(Color.parseColor(paper.background))
        applyAppearanceIfNeeded()
    }

    private fun applyAppearanceIfNeeded() {
        if (!ready || released || pending || desiredAppearance == appliedAppearance) return
        val target = desiredAppearance
        pending = true
        evaluateJavascript("JSON.stringify(window.__moonlight.appearance(${target.json()}))") { value ->
            pending = false
            if (!released) {
                val json = decode(value)
                if (json == null) onError("Reading appearance couldn’t be updated. Please reopen this book.")
                else {
                    appliedAppearance = target
                    accept(json)
                    placeNamedChapters() // A different page size moves the book's own chapters.
                    applyAppearanceIfNeeded() // Coalesce rapid size taps without dropping the final value.
                }
            }
        }
    }

    fun dismissSelection(): Boolean {
        val selecting = selectionMode != null
        selectionMode?.finish()
        if (selecting) annotationQuery("clearSelection()") {}
        return selecting
    }

    fun updateHighlights(highlights: List<BookHighlight>) {
        val next = highlights.filter { it.passage.chapter == chapter }
        if (next == chapterHighlights) return
        chapterHighlights = next
        if (ready && !released) annotationQuery("setHighlights(${JSONArray(next.map { highlightJson(it) })})") {}
    }

    private fun annotationQuery(command: String, result: (JSONObject?) -> Unit) {
        if (!ready || released) return
        evaluateJavascript("JSON.stringify(window.__moonlight.$command)") { value ->
            if (!released) result(decode(value))
        }
    }

    /** Replaces the book's own chapters for this file; the reader may learn them after it opened. */
    fun updateNamedChapters(chapters: List<ChapterLink>) {
        val next = chapters.filterNot { it.fragment.isNullOrEmpty() }
        if (next == chapterLinks) return
        chapterLinks = next
        placeNamedChapters()
    }

    /**
     * Asks the page where each of the book's own chapters begins. Only what the page can place is
     * reported, so an anchor this file does not carry can never pass itself off as page one.
     */
    private fun placeNamedChapters() {
        if (!ready || released) return
        if (chapterLinks.isEmpty()) { onNamedChapters(emptyList()); return }
        val request = JSONArray()
        chapterLinks.forEachIndexed { index, link ->
            request.put(JSONObject().put("id", index).put("fragment", link.fragment))
        }
        evaluateJavascript("JSON.stringify(window.__moonlight.chapterPages($request))") { value ->
            if (released) return@evaluateJavascript
            val placed = decodeArray(value) ?: return@evaluateJavascript
            val chapters = (0 until placed.length()).mapNotNull { position ->
                val item = placed.optJSONObject(position) ?: return@mapNotNull null
                val link = chapterLinks.getOrNull(item.optInt("id", -1)) ?: return@mapNotNull null
                NamedChapter(link.title, item.optInt("page", 0).coerceAtLeast(0))
            }
            onNamedChapters(chapters)
        }
    }

    internal fun captureSelection() {
        annotationQuery("selection()") { json ->
            val start = readAnchor(json?.optJSONObject("start"))
            val end = readAnchor(json?.optJSONObject("end"))
            val passage = if (start != null && end != null) SelectedPassage(chapter, start, end,
                json?.optString("text").orEmpty(), (json?.optDouble("fraction", 0.0) ?: 0.0).toFloat()) else null
            selectionMode?.finish()
            annotationQuery("clearSelection()") {}
            if (passage != null && validPassage(passage)) onSelection(passage)
            else onAnnotationError(json?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "Long-press a word, then adjust the selection handles to choose a passage.")
        }
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        startActionMode(callback, ActionMode.TYPE_PRIMARY)

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        val wrapped = object : ActionMode.Callback2() {
            override fun onGetContentRect(mode: ActionMode, view: android.view.View, outRect: Rect) {
                if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
            private fun addHighlight(menu: Menu) {
                if (menu.findItem(HIGHLIGHT_ACTION) == null) {
                    menu.add(Menu.NONE, HIGHLIGHT_ACTION, 0, "Highlight")
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                menu.findItem(HIGHLIGHT_ACTION)?.isEnabled = ready
            }
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val created = callback.onCreateActionMode(mode, menu)
                if (created) { selectionMode = mode; addHighlight(menu) }
                return created
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val changed = callback.onPrepareActionMode(mode, menu)
                addHighlight(menu)
                return changed
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == HIGHLIGHT_ACTION) { captureSelection(); return true }
                return callback.onActionItemClicked(mode, item)
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                if (selectionMode === mode) selectionMode = null
                callback.onDestroyActionMode(mode)
            }
        }
        return super.startActionMode(wrapped, type)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipBounds = Rect(0, 0, w, h)
        if (ready && !released && w > 0 && h > 0) {
            removeCallbacks(reflow)
            postDelayed(reflow, 80)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) return true
        // Selection handles must receive drag events instead of being treated as page swipes.
        if (selectionMode != null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downTime = event.eventTime; moved = false
                swipeTracker?.recycle()
                swipeTracker = VelocityTracker.obtain().apply { addMovement(event) }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                swipeTracker?.addMovement(event)
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    if (!moved) {
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    moved = true
                    // A sideways swipe is read as it goes, but nothing turns until it is let go, so
                    // a page turns the same way whether it was swiped or tapped.
                    if (swipeDirection == 0 && swipeToTurn) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dx) > abs(dy)) {
                            swipeDirection = if (dx < 0) 1 else -1
                            swipeOffset = 0f
                        }
                    }
                    if (swipeDirection != 0) {
                        val dx = event.x - downX
                        swipeOffset = (if (swipeDirection > 0) -dx else dx).coerceAtLeast(0f)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = swipeTracker
                swipeTracker = null
                if (moved) {
                    if (swipeDirection != 0) {
                        val direction = swipeDirection
                        swipeDirection = 0
                        val width = width.toFloat()
                        // How fast the finger was travelling when it let go, measured the way the
                        // platform measures a fling, so a quick flick turns the page however short
                        // it was without the finger's own slowing down at the end spoiling it.
                        val speed = if (tracker == null) 0f else {
                            tracker.addMovement(event)
                            tracker.computeCurrentVelocity(1000)
                            (-tracker.xVelocity * direction / 1000f).coerceAtLeast(0f)
                        }
                        // A swipe carried far enough, or let go fast enough, turns the page — and the
                        // turn it gets is exactly the turn an edge tap gets.
                        if (event.actionMasked == MotionEvent.ACTION_UP && width > 0f &&
                            swipeCompletes(swipeOffset / width, speed * 1000f / width)) {
                            turnPage(direction)
                        }
                    }
                    tracker?.recycle()
                    return true
                }
                tracker?.recycle()
            }
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !moved && ready && !pending) {
            val scale = resources.displayMetrics.density
            // A long press belongs to text selection, so only a quick tap turns a page.
            val quick = event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
            val zone = if (tapToTurn && quick && width > 0) when {
                event.x < width * EDGE_ZONE -> -1
                event.x > width * (1f - EDGE_ZONE) -> 1
                else -> 0
            } else 0
            annotationQuery("hitHighlight(${event.x / scale},${event.y / scale})") { hit ->
                val id = hit?.optString("id")?.takeIf { it.isNotEmpty() }
                when {
                    id != null -> onHighlightTapped(id)
                    // The WebView has already been handed the link, so the tap is not ours.
                    hit?.optBoolean("link") == true -> Unit
                    zone != 0 -> turnPage(zone)
                    // A live text selection owns the tap that would otherwise reach the controls.
                    hit?.optBoolean("selection") == true -> Unit
                    else -> onMiddleTap?.invoke()
                }
            }
        }
        return handled
    }

    // A page may move only through the pagination controller, never WebView's scrolling.
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (l != 0 || t != 0) scrollTo(0, 0)
    }
    override fun canScrollVertically(direction: Int) = false
    override fun canScrollHorizontally(direction: Int) = false
    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) true else super.onGenericMotionEvent(event)

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { turnPage(1); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { turnPage(-1); true }
        else -> super.performAccessibilityAction(action, arguments)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> { turnPage(1); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> { turnPage(-1); true }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
        else -> super.onKeyDown(keyCode, event)
    }

    fun release() {
        removeCallbacks(poll)
        removeCallbacks(reflow)
        savePosition()
        selectionMode?.finish()
        released = true
        snapshot = null
        stopLoading()
        destroy()
    }

    companion object {
        private const val HOST = "books.moonlightpages.invalid"
        private const val HIGHLIGHT_ACTION = 0x4c414d50

        /** Share of the page width that turns a page when tapped. */
        private const val EDGE_ZONE = 0.24f
        private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
            emptyMap(), "Content unavailable".byteInputStream())
    }
}

/** The window this context is shown in, walked out of whatever wrappers the platform has put on it. */
private fun hostingWindow(context: Context): Window? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return null
}
