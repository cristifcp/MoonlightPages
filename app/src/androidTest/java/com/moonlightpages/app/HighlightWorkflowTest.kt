package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HighlightWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    private lateinit var book: Book
    private lateinit var view: BookWebView
    private var opened by mutableStateOf(true)

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "highlight-workflow-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
        book = sampleBook(store)
    }

    @After fun cleanup() {
        context.getSharedPreferences("reading", Context.MODE_PRIVATE).edit().clear().commit()
        folder.deleteRecursively()
    }

    private fun awaitReader() {
        compose.awaitReadingPage()
        onView(isAssignableFrom(BookWebView::class.java)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(BookWebView::class.java)
            override fun getDescription() = "Find the private test reader"
            override fun perform(controller: UiController, target: View) { view = target as BookWebView }
        })
    }

    private fun inspect(expression: String): JSONObject {
        val output = AtomicReference<String>()
        val done = CountDownLatch(1)
        compose.runOnIdle { view.evaluateJavascript("JSON.stringify($expression)") { output.set(it); done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return JSONObject(JSONTokener(output.get()).nextValue() as String)
    }

    private fun wordPress(type: Tap) {
        val box = inspect("""(() => {
            const node=document.getElementById('__moonlightPages').querySelector('p').firstChild;
            const start=node.data.indexOf('particular'); const range=document.createRange();
            range.setStart(node,start);range.setEnd(node,start+10); const rect=range.getBoundingClientRect();
            return {x:rect.left+rect.width/2,y:rect.top+rect.height/2,w:innerWidth};
        })()""".trimIndent())
        onView(isAssignableFrom(BookWebView::class.java)).perform(GeneralClickAction(type,
            CoordinatesProvider { target ->
                val origin = IntArray(2).also { target.getLocationOnScreen(it) }
                val scale = target.width / box.getDouble("w")
                floatArrayOf((origin[0] + box.getDouble("x") * scale).toFloat(),
                    (origin[1] + box.getDouble("y") * scale).toFloat())
            }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, 0))
    }

    private fun chooseNativeHighlightAction() {
        // Floating text toolbars can be rendered by Android's remote toolbar service,
        // outside Espresso's in-process View hierarchy.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        val oldFlags = info.flags
        info.flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        try {
            var action: AccessibilityNodeInfo? = null
            compose.waitUntil(10_000) {
                action = automation.windows.asSequence().mapNotNull { it.root }
                    .flatMap { it.findAccessibilityNodeInfosByText("Highlight").asSequence() }
                    .firstOrNull { it.text?.toString() == "Highlight" }
                action != null
            }
            var target = action
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
                val bounds = android.graphics.Rect().also { action!!.getBoundsInScreen(it) }
                val now = android.os.SystemClock.uptimeMillis()
                for (kind in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), kind,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
            }
        } finally {
            info.flags = oldFlags
            automation.serviceInfo = info
        }
    }

    private fun awaitDialog(title: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitPaint(color: HighlightColor, count: Int) {
        compose.waitUntil(10_000) {
            inspect("({count:CSS.highlights.get('__moonlight${color.name}')?.size || 0})").getInt("count") == count
        }
    }

    @Test fun longPressSavesHighlightAndTapCanRecolorAndDeleteAfterReopening() {
        compose.setContent { MaterialTheme { if (opened) ReaderScreen(book, store) {} } }
        awaitReader()
        wordPress(Tap.LONG)
        chooseNativeHighlightAction()
        awaitDialog("Highlight passage")
        compose.onNodeWithText("Highlight passage").assertExists()
        compose.onNodeWithText("Yellow").performClick()
        compose.waitUntil(10_000) { store.highlights.load(book).size == 1 }
        awaitPaint(HighlightColor.YELLOW, 1)
        assertEquals("particular", store.highlights.load(book).single().passage.text)
        wordPress(Tap.SINGLE)
        awaitDialog("Edit highlight")
        compose.onNodeWithText("Edit highlight").assertExists()
        compose.onNodeWithText("Rose").performClick()
        compose.waitUntil(10_000) { store.highlights.load(book).single().color == HighlightColor.ROSE }
        awaitPaint(HighlightColor.ROSE, 1)
        val saved = store.highlights.load(book).single()
        compose.runOnIdle { opened = false }
        compose.waitForIdle()
        compose.runOnIdle { opened = true }
        awaitReader()
        awaitPaint(HighlightColor.ROSE, 1)
        compose.showReaderControls()
        compose.onNodeWithText("Aa").performClick()
        compose.onNodeWithText("A+").performClick()
        compose.onNodeWithText("Done").performClick()
        awaitReader()
        assertEquals(saved, store.highlights.load(book).single())
        wordPress(Tap.SINGLE)
        awaitDialog("Edit highlight")
        compose.onNodeWithText("Edit highlight").assertExists()
        compose.onNodeWithText("Delete highlight").performClick()
        compose.waitUntil(10_000) { store.highlights.load(book).isEmpty() }
        awaitPaint(HighlightColor.ROSE, 0)
        val painted = inspect("({count:CSS.highlights.get('__moonlightROSE')?.size || 0})")
        assertEquals(0, painted.getInt("count"))
    }

    @Test fun highlightsAcrossInlineElementsDoNotChangeTextNodesOrPageCount() {
        val chapter = File(store.folder(book), "one.xhtml")
        chapter.writeText(chapter.readText().replace("particular kind", "particular <em>kind</em>"))
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        val before = inspect("""(() => {
            const p=document.getElementById('__moonlightPages').querySelector('p');const selection=getSelection();
            const range=document.createRange();range.setStart(p.firstChild,p.firstChild.data.indexOf('particular'));
            range.setEnd(p.querySelector('em').firstChild,4);selection.removeAllRanges();selection.addRange(range);
            return {pages:__moonlight.snapshot().pages,html:document.getElementById('__moonlightPages').innerHTML};
        })()""".trimIndent())
        compose.runOnIdle { view.captureSelection() }
        awaitDialog("Highlight passage")
        compose.onNodeWithText("Highlight passage").assertExists()
        compose.onNodeWithText("Mint").performClick()
        compose.waitUntil(10_000) { store.highlights.load(book).size == 1 }
        awaitPaint(HighlightColor.MINT, 1)
        assertEquals("particular kind", store.highlights.load(book).single().passage.text)
        val after = inspect("({pages:__moonlight.snapshot().pages,html:document.getElementById('__moonlightPages').innerHTML,count:CSS.highlights.get('__moonlightMINT')?.size || 0})")
        assertEquals(before.getInt("pages"), after.getInt("pages"))
        assertEquals(before.getString("html"), after.getString("html"))
        assertEquals(1, after.getInt("count"))

        compose.showReaderControls()
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithContentDescription("Go to One More Page").performClick()
        awaitReader()
        assertEquals(1, store.chapter(book))
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithText("Highlights (1)").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Open highlight: particular kind").performClick()
        awaitReader()
        assertEquals(0, store.chapter(book))
        assertEquals(store.highlights.load(book).single().passage.start, store.anchor(book))
        awaitPaint(HighlightColor.MINT, 1)
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithText("Highlights (1)").performScrollTo().performClick()
        compose.onNodeWithText("Edit", substring = false).performClick()
        awaitDialog("Edit highlight")
        compose.onNodeWithText("Lavender").performClick()
        compose.waitUntil(10_000) { store.highlights.load(book).single().color == HighlightColor.LAVENDER }
        awaitPaint(HighlightColor.LAVENDER, 1)
    }

    @Test fun aNoteTypedWhileMarkingAPassageIsSavedWithIt() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        wordPress(Tap.LONG)
        chooseNativeHighlightAction()
        awaitDialog("Highlight passage")
        compose.onNode(hasSetTextAction()).performTextInput("Worth remembering")
        compose.onNodeWithText("Yellow").performClick()
        compose.waitUntil(10_000) { store.highlights.load(book).size == 1 }
        val mark = store.highlights.load(book).single()
        assertEquals("Worth remembering", mark.note)
        assertEquals(HighlightColor.YELLOW, mark.color)
        awaitPaint(HighlightColor.YELLOW, 1)
    }

    @Test fun aNoteIsWrittenOnAnExistingHighlightAndGatheredInTheNotebook() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        wordPress(Tap.LONG)
        chooseNativeHighlightAction()
        awaitDialog("Highlight passage")
        compose.onNodeWithText("Mint").performClick()
        compose.waitUntil(10_000) { store.highlights.load(book).size == 1 }
        awaitPaint(HighlightColor.MINT, 1)
        val plain = inspect("({rules:document.getElementById('__moonlightHighlights').children.length})").getInt("rules")
        wordPress(Tap.SINGLE)
        awaitDialog("Edit highlight")
        compose.onNode(hasSetTextAction()).performTextInput("The quiet I was looking for")
        compose.onNodeWithText("Save note").performClick()
        compose.waitUntil(10_000) { store.highlights.load(book).single().note == "The quiet I was looking for" }
        // The marked passage now carries a line under it, so the note is visible on the page too.
        compose.waitUntil(10_000) {
            inspect("({rules:document.getElementById('__moonlightHighlights').children.length})").getInt("rules") > plain
        }
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithText("Notebook (1)").performScrollTo().performClick()
        compose.onNodeWithText("The quiet I was looking for").assertExists()
        compose.onNodeWithText("Edit note").assertExists()
        // And the notebook entry returns to the words it was written about.
        compose.onNodeWithContentDescription("Open highlight: particular").performClick()
        awaitReader()
        val saved = store.highlights.load(book).single()
        assertEquals(saved.passage.start, store.anchor(book))
        assertEquals("The quiet I was looking for", saved.note)
    }

    @Test fun aNoteCanBeTakenBackFromTheNotebook() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        wordPress(Tap.LONG)
        chooseNativeHighlightAction()
        awaitDialog("Highlight passage")
        compose.onNode(hasSetTextAction()).performTextInput("A thought I changed my mind about")
        compose.onNodeWithText("Rose").performClick()
        compose.waitUntil(10_000) {
            store.highlights.load(book).singleOrNull()?.note == "A thought I changed my mind about"
        }
        awaitPaint(HighlightColor.ROSE, 1)
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithText("Notebook (1)").performScrollTo().performClick()
        compose.onNodeWithText("Edit note").performClick()
        awaitDialog("Edit highlight")
        compose.onNodeWithContentDescription("Clear note").performClick()
        compose.onNodeWithText("Remove note").performClick()
        compose.waitUntil(10_000) { store.highlights.load(book).single().note.isEmpty() }
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Contents").performClick()
        // The chips scroll sideways, so the notebook chip has to be brought into view to be tapped.
        compose.onNodeWithText("Notebook (0)").performScrollTo().performClick()
        val notesTab = compose.onAllNodesWithText("Your notes gather here.", substring = true).fetchSemanticsNodes()
        if (notesTab.isEmpty()) {
            // A failure here is about what the sheet shows, so say what it does show.
            val chips = compose.onAllNodesWithText("Notebook", substring = true).fetchSemanticsNodes().size
            val chapters = compose.onAllNodesWithText("Chapters").fetchSemanticsNodes().size
            fail("No empty notebook on screen: $chips notebook chips, $chapters chapter chips")
        }
        assertEquals(1, store.highlights.load(book).size)
    }

    @Test fun theNotebookNamesTheChapterTheWayTheBookDoes() {
        // Marked words keep the name the book gives their chapter, not a number from the file they
        // happen to live in — one file of a book can hold a dozen named chapters.
        store.highlights.add(book, SelectedPassage(0, TextAnchor(1, 13, "particular"), TextAnchor(1, 23),
            "particular", .2f, "One More Page"), HighlightColor.MINT, "A thought worth keeping.")
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithText("Notebook (1)").performScrollTo().performClick()
        compose.onNodeWithText("A thought worth keeping.").assertExists()
        compose.onNodeWithText("Mint · One More Page · Your note").assertExists()
        compose.onNodeWithText("Highlights (1)").performScrollTo().performClick()
        compose.onNodeWithText("Mint · One More Page · Your note").assertExists()
    }

    /** The marker says which chapter the marked words came from, not just what they say. */
    @Test fun theMarkerNamesTheChapterTheWordsCameFrom() {
        val grouped = store.importBook(groupedChaptersEpubBytes().inputStream())
        compose.setContent { MaterialTheme { ReaderScreen(grouped, store) {} } }
        awaitReader()
        inspect("""(() => {
            const node=document.getElementById('__moonlightPages').querySelector('p').firstChild;
            const range=document.createRange();range.setStart(node,0);range.setEnd(node,12);
            getSelection().removeAllRanges();getSelection().addRange(range);
            return __moonlight.snapshot();
        })()""".trimIndent())
        compose.runOnIdle { view.captureSelection() }
        awaitDialog("Highlight passage")
        compose.onNodeWithText("Chapter One").assertExists()
    }

    /** A file can hold several of the book's chapters, so the page number counts the chapter. */
    @Test fun thePageNumberCountsTheChapterRatherThanTheWholeFile() {
        val grouped = store.importBook(groupedChaptersEpubBytes().inputStream())
        compose.setContent { MaterialTheme { ReaderScreen(grouped, store) {} } }
        awaitReader()
        compose.showReaderControls()
        val pages = compose.runOnIdle { requireNotNull(view.currentLocation()).pages }
        // The book's own chapters are placed a moment after the page is laid out, and the number
        // belongs to the chapter once they are: wait for it to stop counting the whole file.
        compose.waitUntil(20_000) { labelText().let { it.isNotEmpty() && it != "1 of $pages" } }
        val total = labelText().substringAfter(" of ").trim().toInt()
        assertTrue("The page number counts the chapter ($total), not the whole file of $pages pages",
            total in 1 until pages)
    }

    /** The page number the reader is showing, read rather than only looked for. */
    private fun labelText(): String {
        // Only the page number itself ("12 of 40"), not a hint that happens to contain the word "of".
        val pageNumber = Regex("""\d+ of \d+""")
        val node = compose.onAllNodes(SemanticsMatcher("page number") { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            pageNumber.matches(text)
        }).fetchSemanticsNodes().firstOrNull()
        return node?.config?.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    @Test fun aMarkedPassageKeepsTheBooksOwnNameForTheChapterItIsIn() {        // One file of this book holds three named chapters, the way real novels often do.
        val grouped = store.importBook(groupedChaptersEpubBytes().inputStream())
        compose.setContent { MaterialTheme { ReaderScreen(grouped, store) {} } }
        awaitReader()
        compose.showReaderControls()
        // The book's names arrive with its navigation, and the page each one begins on is worked out
        // a moment later. The hint counts against the chapter once that is known, so waiting for it
        // to stop counting against the whole file means waiting for the reader to know the chapter.
        val pages = compose.runOnIdle { requireNotNull(view.currentLocation()).pages }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(fileWideHint(pages)).fetchSemanticsNodes().isEmpty()
        }
        // The heading this file carries calls it "Chapter 1"; the book's own contents call it
        // "Chapter One", so a mark keeping that name cannot have taken it from the page.
        assertEquals("Chapter 1", inspect("({h:document.getElementById('first').textContent})").getString("h"))
        inspect("""(() => {
            const node=document.getElementById('__moonlightPages').querySelector('p').firstChild;
            const range=document.createRange();range.setStart(node,0);range.setEnd(node,12);
            getSelection().removeAllRanges();getSelection().addRange(range);
            return __moonlight.snapshot();
        })()""".trimIndent())
        compose.runOnIdle { view.captureSelection() }
        awaitDialog("Highlight passage")
        compose.onNodeWithText("Mint").performClick()
        compose.waitUntil(10_000) { store.highlights.load(grouped).size == 1 }
        val mark = store.highlights.load(grouped).single()
        assertEquals("Chapter One", mark.passage.title)
        assertEquals("Chapter One", passageChapter(mark.passage))
    }

    /** The hint is worded this way while the reader still counts to the end of the file. */
    private fun fileWideHint(pages: Int) = when (val left = (pages - 1).coerceAtLeast(0)) {
        0 -> "Last page of this chapter"
        1 -> "1 page left in this chapter"
        else -> "$left pages left in this chapter"
    }

    @Test fun mismatchedQuoteIsNotPaintedOverUnrelatedWords() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        val selected = inspect("""(() => {
            const node=document.getElementById('__moonlightPages').querySelector('p').firstChild;
            const index=node.data.indexOf('particular');const range=document.createRange();
            range.setStart(node,index);range.setEnd(node,index+10);getSelection().removeAllRanges();getSelection().addRange(range);
            return __moonlight.selection();
        })()""".trimIndent())
        val passage = SelectedPassage(0, readAnchor(selected.getJSONObject("start"))!!.copy(quote = "different text"),
            readAnchor(selected.getJSONObject("end"))!!, selected.getString("text"), 0f)
        val marks = store.highlights.add(book, passage, HighlightColor.YELLOW)
        compose.runOnIdle { view.updateHighlights(marks) }
        awaitPaint(HighlightColor.YELLOW, 0)
        assertEquals(1, store.highlights.load(book).size)
    }
}
