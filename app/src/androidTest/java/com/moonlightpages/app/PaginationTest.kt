package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PaginationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    private lateinit var book: Book
    private lateinit var view: BookWebView
    private val latest = AtomicReference<ReadingLocation?>()
    private val readyCount = AtomicInteger()
    private val boundary = AtomicInteger()
    private val failure = AtomicReference<String?>()
    private val console = AtomicReference("")
    private val placed = AtomicReference<List<NamedChapter>>(emptyList())
    private var font by mutableIntStateOf(20)
    private var shorter by mutableStateOf(false)
    private var reopen by mutableIntStateOf(0)

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "pagination-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
        book = sampleBook(store)
        // A long chapter makes multiple page turns and reflow checks independent of screen size.
        val chapter = File(store.folder(book), "one.xhtml")
        chapter.writeText(chapter.readText().replace("</body>", (1..30).joinToString("") {
            "<p>Paragraph $it. A lamp by the window lights the page. Every sentence has its own place, even as the shape of the page changes. There is always another small detail to discover.</p>"
        } + "</body>"))
    }

    @After fun cleanup() {
        context.getSharedPreferences("reading", Context.MODE_PRIVATE).edit().clear().commit()
        folder.deleteRecursively()
    }

    private fun mount(named: List<ChapterLink> = emptyList()) {
        compose.setContent {
            key(font, reopen) {
                AndroidView(modifier = Modifier.fillMaxWidth().height(if (shorter) 400.dp else 600.dp),
                    factory = { activity ->
                        val previous = latest.get()
                        BookWebView(activity, store.folder(book), book, 0, font, papers[1],
                            previous?.fraction ?: 0f, onPosition = { latest.set(it) }, onChapter = { _, _ -> },
                            initialAnchor = previous?.anchor, onBoundary = { boundary.set(it) },
                            onReady = { readyCount.incrementAndGet() }, onError = { failure.set(it) },
                            namedChapters = named, onNamedChapters = { placed.set(it) }).also {
                            view = it
                            it.webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                                    console.set(console.get() + "\n" + message.message())
                                    return true
                                }
                            }
                        }
                    }, onRelease = { it.release() })
            }
        }
        waitReady(1)
    }

    private fun waitReady(count: Int) {
        compose.waitUntil(20_000) { readyCount.get() >= count || failure.get() != null }
        assertNull("Console: ${console.get()}, geometry: ${inspect("({w:innerWidth,h:innerHeight,api:!!window.__moonlight,ready:window.__moonlight && window.__moonlight.ready})")}", failure.get())
        compose.waitForIdle()
    }

    private fun turn(direction: Int) {
        val old = latest.get()!!.page
        compose.runOnIdle { view.turnPage(direction) }
        compose.waitUntil(10_000) { latest.get()!!.page == old + direction || failure.get() != null }
        assertNull(failure.get())
    }

    private fun inspect(expression: String): JSONObject {
        val output = AtomicReference<String>()
        val done = CountDownLatch(1)
        compose.runOnIdle { view.evaluateJavascript("JSON.stringify($expression)") { output.set(it); done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return JSONObject(JSONTokener(output.get()).nextValue() as String)
    }

    // Start inside the page, not in Android's reserved system-back gesture strip.
    private fun swipeAcross(left: Boolean) = GeneralSwipeAction(Swipe.FAST,
        CoordinatesProvider { target ->
            val origin = IntArray(2).also { target.getLocationOnScreen(it) }
            floatArrayOf(origin[0] + target.width * (if (left) .8f else .2f), origin[1] + target.height * .5f)
        }, CoordinatesProvider { target ->
            val origin = IntArray(2).also { target.getLocationOnScreen(it) }
            floatArrayOf(origin[0] + target.width * (if (left) .2f else .8f), origin[1] + target.height * .5f)
        }, Press.FINGER)

    /** A tap over a link belongs to the link, so the reader must not turn the page under it. */
    @Test fun hitHighlightReportsLinksAndAlwaysAnswersWithAnObject() {
        val chapter = File(store.folder(book), "one.xhtml")
        chapter.writeText(chapter.readText()
            .replace("<body>", """<body><p><a href="one.xhtml#top">A linked phrase</a></p>"""))
        mount()
        // inspect() stringifies the expression itself, so it must evaluate to a plain object.
        val plain = inspect("window.__moonlight.hitHighlight(4, 4)")
        assertEquals("", plain.getString("id"))
        assertFalse(plain.getBoolean("link"))
        assertFalse(plain.getBoolean("selection"))
        // A tap that dismisses a text selection belongs to the selection, not to the reader.
        inspect("""(() => {const node=document.querySelector('#__moonlightPages p a').firstChild;
            const r=document.createRange();r.setStart(node,0);r.setEnd(node,5);
            getSelection().removeAllRanges();getSelection().addRange(r);return {ok:true};})()""".trimIndent())
        val choosing = inspect("window.__moonlight.hitHighlight(4, 4)")
        assertTrue(choosing.getBoolean("selection"))
        assertFalse(choosing.getBoolean("link"))
        val box = inspect("(function(){const a=document.querySelector('a[href]');" +
            "const r=a.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()")
        val overLink = inspect(
            "window.__moonlight.hitHighlight(${box.getDouble("x")},${box.getDouble("y")})")
        assertTrue(overLink.getBoolean("link"))
    }

    @Test fun theBooksOwnChaptersArePlacedWhereTheyBeginInsideTheFile() {
        val chapter = File(store.folder(book), "one.xhtml")
        chapter.writeText(chapter.readText()
            .replace("<body>", """<body><h1 id="first">First Chapter</h1>""")
            .replace("<p>Paragraph 15.", """<h1 id="middle">Middle Chapter</h1><p>Paragraph 15."""))
        mount(listOf(ChapterLink("First Chapter", 0, "first"), ChapterLink("Middle Chapter", 0, "middle"),
            ChapterLink("Not in this file", 0, "missing")))
        compose.waitUntil(10_000) { placed.get().isNotEmpty() }
        val pages = latest.get()!!.pages
        assertTrue("The chapter must run over several pages to place a chapter inside it", pages > 5)
        val marks = placed.get()
        // An anchor this file does not carry is left out rather than reported as page one.
        assertEquals(listOf("First Chapter", "Middle Chapter"), marks.map { it.title })
        assertEquals(0, marks.first().page)
        assertTrue("The middle chapter begins inside the file, not at its end",
            marks.last().page in 1 until pages)
    }

    @Test fun horizontalSwipesTurnPagesAndVerticalSwipesDoNothing() {
        mount()
        assertTrue(latest.get()!!.pages > 3)
        assertTrue("A chapter must not fragment into one-line pages", latest.get()!!.pages < 50)
        assertNotNull(inspect("({w:innerWidth,h:innerHeight, state:window.__moonlight.snapshot(), rect:document.getElementById('__moonlightPages').getBoundingClientRect().toJSON(), html:document.getElementById('__moonlightPages').innerHTML.slice(0,300)})").toString(), latest.get()!!.anchor)
        val first = latest.get()
        onView(isAssignableFrom(BookWebView::class.java)).perform(swipeUp(), swipeDown())
        assertEquals(first, latest.get())
        assertEquals(0, view.scrollY)
        onView(isAssignableFrom(BookWebView::class.java)).perform(swipeAcross(true))
        compose.waitUntil(10_000) { latest.get()!!.page == 1 }
        assertNotEquals(first!!.anchor, latest.get()!!.anchor)
        onView(isAssignableFrom(BookWebView::class.java)).perform(swipeAcross(false))
        compose.waitUntil(10_000) { latest.get()!!.page == 0 }
        assertEquals(first.anchor, latest.get()!!.anchor)
        val document = inspect("({x:scrollX,y:scrollY, overflow:getComputedStyle(document.documentElement).overflow})")
        assertEquals(0, document.getInt("x"))
        assertEquals(0, document.getInt("y"))
        assertEquals("hidden", document.getString("overflow"))
    }

    @Test fun textAnchorSurvivesFontSizeChangesReopeningAndViewportResize() {
        mount()
        turn(1); turn(1)
        val original = latest.get()!!
        val anchor = original.anchor
        assertNotNull(anchor)
        compose.runOnIdle { font = 30 }
        waitReady(2)
        assertEquals(anchor, latest.get()!!.anchor)
        assertTrue(latest.get()!!.pages > original.pages)
        val beforeResize = latest.get()!!
        compose.runOnIdle { shorter = true }
        try {
            compose.waitUntil(10_000) { latest.get()!!.pages > beforeResize.pages }
        } catch (error: Throwable) {
            throw AssertionError("Before resize: $beforeResize, now: ${latest.get()}, geometry: ${inspect("({w:innerWidth,h:innerHeight, state:window.__moonlight.snapshot(), rect:document.getElementById('__moonlightPages').getBoundingClientRect().toJSON()})")}, native height: ${view.height}", error)
        }
        assertEquals(anchor, latest.get()!!.anchor)
        compose.runOnIdle { reopen++ }
        waitReady(3)
        assertEquals(anchor, latest.get()!!.anchor)
        assertTrue(latest.get()!!.page > 0)
        compose.runOnIdle { font = 20 }
        waitReady(4)
        assertEquals(anchor, latest.get()!!.anchor)
    }

    @Test fun documentScriptsCannotRunButTrustedPaginationCan() {
        val chapter = File(store.folder(book), "one.xhtml")
        chapter.writeText(chapter.readText().replace("</body>", """
            <script>window.untrustedBookScript = true;</script>
            <img src="missing.jpg" onerror="window.untrustedBookScript=true"/>
            </body>
        """.trimIndent()))
        mount()
        val result = inspect("({untrusted:!!window.untrustedBookScript, trusted:window.__moonlight.ready, scripts:document.scripts.length})")
        assertFalse(result.getBoolean("untrusted"))
        assertTrue(result.getBoolean("trusted"))
        assertEquals(0, result.getInt("scripts"))
        turn(1)
        assertEquals(1, latest.get()!!.page)
    }

    @Test fun everyPageAdvancesThroughTextAndStopsAtChapterBoundaries() {
        mount()
        compose.runOnIdle { view.turnPage(-1) }
        compose.waitUntil(10_000) { boundary.get() == -1 }
        val pages = latest.get()!!.pages
        var previous = latest.get()!!.start
        repeat(pages - 1) {
            turn(1)
            val current = latest.get()!!.start
            if (previous != null && current != null) assertTrue(compareAnchors(previous!!, current) < 0)
            previous = current
        }
        assertEquals(pages - 1, latest.get()!!.page)
        compose.runOnIdle { view.turnPage(1) }
        compose.waitUntil(10_000) { boundary.get() == 1 }
        assertEquals(pages - 1, latest.get()!!.page)
        assertEquals(0, view.scrollY)
    }

    @Test fun largeRasterAndSvgImagesStayInsideThePageWithoutA3dLayer() {
        addIllustratedChapter(store.folder(book))
        mount()
        val initial = inspect("({pages:window.__moonlight.snapshot().pages, images:Array.from(document.images).map(image=>({complete:image.complete,w:image.naturalWidth,loading:image.loading})), transform:getComputedStyle(document.getElementById('__moonlightPages')).transform})")
        assertEquals("none", initial.getString("transform"))
        val images = initial.getJSONArray("images")
        assertEquals(6, images.length())
        repeat(images.length()) { index ->
            assertTrue(images.getJSONObject(index).getBoolean("complete"))
            assertTrue(images.getJSONObject(index).getInt("w") > 0)
            assertEquals("eager", images.getJSONObject(index).getString("loading"))
        }
        val pages = latest.get()!!.pages
        assertTrue(pages > 5)
        repeat(pages - 1) {
            turn(1)
            val state = inspect("({pages:window.__moonlight.snapshot().pages, transform:getComputedStyle(document.getElementById('__moonlightPages')).transform, x:scrollX,y:scrollY,oversize:Array.from(document.images).some(image=>image.getBoundingClientRect().height>innerHeight-39)})")
            assertEquals(pages, state.getInt("pages"))
            assertEquals("none", state.getString("transform"))
            assertEquals(0, state.getInt("x"))
            assertEquals(0, state.getInt("y"))
            assertFalse(state.getBoolean("oversize"))
            compose.runOnIdle {
                assertTrue(view.clipToOutline)
                assertEquals(android.graphics.Rect(0, 0, view.width, view.height), view.clipBounds)
            }
        }
        turn(-1)
    }

    @Test fun appearanceChangesUpdateTheSameRendererAndKeepTheTextAnchor() {
        mount()
        turn(1); turn(1)
        val original = latest.get()!!
        val renderer = view
        compose.runOnIdle {
            view.updateAppearance(22, papers[1])
            view.updateAppearance(30, papers[2])
            view.updateAppearance(28, papers[2])
        }
        compose.waitUntil(10_000) {
            inspect("({font:getComputedStyle(document.getElementById('__moonlightPages')).fontSize})")
                .getString("font") == "28px" && latest.get()!!.pages > original.pages
        }
        assertSame(renderer, view)
        assertEquals(1, readyCount.get())
        assertEquals(original.anchor, latest.get()!!.anchor)
        val night = inspect("({paper:getComputedStyle(document.body).backgroundColor,ink:getComputedStyle(document.getElementById('__moonlightPages')).color})")
        assertEquals("rgb(20, 26, 38)", night.getString("paper"))
        assertEquals("rgb(228, 232, 239)", night.getString("ink"))
        turn(1)
        assertEquals(0, view.scrollY)
    }

    /** Room, line spacing and face all reach the page, and it is laid out again around them. */
    @Test fun thePageCanBeGivenMoreRoomLooserLinesAndAnotherFace() {
        mount()
        turn(1); turn(1)
        val before = latest.get()!!
        val roomy = inspect("({gap:parseFloat(getComputedStyle(document.getElementById('__moonlightPages')).columnGap)," +
            "line:parseFloat(getComputedStyle(document.getElementById('__moonlightPages')).lineHeight)," +
            "face:getComputedStyle(document.getElementById('__moonlightPages')).fontFamily})")
        assertEquals("A page keeps 24px at each side by default", 48.0, roomy.getDouble("gap"), 0.5)
        assertTrue("and is printed in a serif face", roomy.getString("face").contains("serif"))

        compose.runOnIdle { view.updateAppearance(20, papers[1], spacing = 2, inset = 2, font = 1) }
        compose.waitUntil(10_000) {
            inspect("({gap:parseFloat(getComputedStyle(document.getElementById('__moonlightPages')).columnGap)})")
                .getDouble("gap") > 70.0
        }
        val wide = inspect("({gap:parseFloat(getComputedStyle(document.getElementById('__moonlightPages')).columnGap)," +
            "line:parseFloat(getComputedStyle(document.getElementById('__moonlightPages')).lineHeight)," +
            "face:getComputedStyle(document.getElementById('__moonlightPages')).fontFamily," +
            "pages:window.__moonlight.snapshot().pages})")
        assertEquals("40px at each side, so the next page is 80px further on", 80.0, wide.getDouble("gap"), 0.5)
        assertEquals("20px text set 1.95 apart is 39px a line", 39.0, wide.getDouble("line"), 1.0)
        assertTrue("and the face is the one asked for", wide.getString("face").contains("sans-serif"))
        assertTrue("a roomier page holds less, so it takes more of them: ${before.pages} then ${wide.getInt("pages")}",
            wide.getInt("pages") > before.pages)
        assertEquals("and the reader is still reading the same words", before.anchor, latest.get()!!.anchor)
        assertEquals(0, view.scrollY)
    }
}
