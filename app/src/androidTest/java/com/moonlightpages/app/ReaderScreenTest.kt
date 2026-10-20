package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ReaderScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    private lateinit var book: Book

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "reader-ui-test-${UUID.randomUUID()}"
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

    private fun awaitReader() = compose.awaitReadingPage()

    private fun reader(): BookWebView {
        awaitReader()
        var found: BookWebView? = null
        onView(isAssignableFrom(BookWebView::class.java)).perform(object : ViewAction {
            override fun getDescription() = "Get the reading page"
            override fun getConstraints() = isAssignableFrom(BookWebView::class.java)
            override fun perform(controller: UiController, view: View) { found = view as BookWebView }
        })
        return found!!
    }

    /** Pages turn by swipe or tap zone now, so tests drive the page controller directly. */
    private fun turn(direction: Int) {
        val page = reader()
        val chapter = store.chapter(book)
        val anchor = store.anchor(book)
        val fraction = store.position(book)
        compose.runOnIdle { page.turnPage(direction) }
        compose.waitUntil(10_000) {
            store.chapter(book) != chapter || store.anchor(book) != anchor || store.position(book) != fraction
        }
    }

    private fun pageSignature() = Triple(store.chapter(book), store.anchor(book), store.position(book))

    /** Only Espresso can deliver a tap to a WebView at a chosen point. */
    private fun tapAt(fractionOfWidth: Float) {
        onView(isAssignableFrom(BookWebView::class.java)).perform(GeneralClickAction(Tap.SINGLE,
            CoordinatesProvider { view -> floatArrayOf(view.width * fractionOfWidth, view.height * 0.5f) },
            Press.FINGER, InputDevice.SOURCE_UNKNOWN, MotionEvent.BUTTON_PRIMARY))
        compose.waitForIdle()
    }

    /** Only Espresso can drag across a WebView the way a finger would. */
    private fun swipeAcross(left: Boolean) = GeneralSwipeAction(Swipe.FAST,
        CoordinatesProvider { view ->
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            floatArrayOf(origin[0] + view.width * (if (left) .8f else .2f), origin[1] + view.height * .5f)
        }, CoordinatesProvider { view ->
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            floatArrayOf(origin[0] + view.width * (if (left) .2f else .8f), origin[1] + view.height * .5f)
        }, Press.FINGER)

    private fun assertNoPageTurn(after: () -> Unit) {
        val before = pageSignature()
        after()
        // The tap round trip runs through the page script, which the test clock does not track.
        Thread.sleep(600)
        compose.waitForIdle()
        assertEquals(before, pageSignature())
    }

    @Test fun tappingTheEdgesTurnsThePageBothWays() {
        store.saveTapToTurn(true)
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        val start = pageSignature()
        tapAt(0.92f)
        compose.waitUntil(10_000) { pageSignature() != start }
        val forward = pageSignature()
        assertEquals("An edge tap must stay inside the open chapter.", 0, store.chapter(book))
        tapAt(0.08f)
        compose.waitUntil(10_000) { pageSignature() != forward }
        assertEquals(0, store.chapter(book))
    }

    @Test fun theMiddleOfThePageIsNotATapZone() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        assertNoPageTurn { tapAt(0.5f) }
    }

    @Test fun edgeTapsStayOffWhenTheSettingIsOff() {
        store.saveTapToTurn(false)
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        assertNoPageTurn { tapAt(0.95f) }
    }

    /** The end of the last chapter is the end of the book, and the shelf says so. */
    @Test fun readingToTheEndOfTheLastChapterFinishesTheBook() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithContentDescription("Go to One More Page").performClick()
        awaitReader()
        assertEquals(1, store.chapter(book))
        var turns = 0
        while (!store.finished(book) && turns++ < 30) turn(1)
        assertTrue("The last page of the last chapter finishes the book.", store.finished(book))
        assertEquals(100, store.progressPercent(book))
    }

    /** Choosing edge taps turns swiping off, so a drag must leave the page exactly where it is. */
    @Test fun swipingStopsTurningPagesWhenEdgeTapsAreChosen() {
        store.saveTapToTurn(true)
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        assertNoPageTurn { onView(isAssignableFrom(BookWebView::class.java)).perform(swipeAcross(true)) }
        // Edge taps are the way that was chosen, so the page still turns.
        val start = pageSignature()
        tapAt(0.92f)
        compose.waitUntil(10_000) { pageSignature() != start }
    }

    @Test fun createsOpensAndDeletesBookmarkAndNavigatesNamedChapters() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Bookmark this place").performClick()
        compose.onNodeWithContentDescription("Remove this bookmark").assertExists()
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithContentDescription("Go to One More Page").performClick()
        awaitReader()
        assertEquals(1, store.chapter(book))
        compose.onNodeWithContentDescription("Bookmarks").performClick()
        compose.onNodeWithText("Bookmarks (1)").performClick()
        compose.onNodeWithContentDescription("Open bookmark: A Quiet Beginning").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Remove this bookmark").fetchSemanticsNodes()
                .any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        assertEquals(0, store.chapter(book))
        compose.onNodeWithContentDescription("Bookmarks").performClick()
        compose.onNodeWithContentDescription("Delete bookmark: A Quiet Beginning").performClick()
        compose.onNodeWithText("Bookmarks (0)").assertExists()
        compose.onNodeWithText("Keep a place worth returning to.", substring = true).assertExists()
    }

    @Test fun bookmarkCanJumpWithinTheSameChapter() {
        store.addBookmark(book, 0, .65f, "Saved halfway through")
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Bookmarks").performClick()
        compose.onNodeWithText("Bookmarks (1)").performClick()
        compose.onNodeWithContentDescription("Open bookmark: Saved halfway through").performClick()
        compose.waitUntil(20_000) { store.bookmarks(book).single().anchor != null }
        compose.waitForIdle()
        assertEquals(0, store.chapter(book))
        assertNotNull(store.anchor(book))
        assertEquals(store.bookmarks(book).single().anchor, store.anchor(book))
    }

    @Test fun tableOfContentsCanJumpToAnAnchorInTheCurrentChapter() {
        val folder = store.folder(book)
        val opf = File(folder, "book.opf")
        opf.writeText(opf.readText().replace("</manifest>",
            """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>"""))
        File(folder, "nav.xhtml").writeText("""
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body><nav epub:type="toc"><ol><li><a href="one.xhtml#ending">The final paragraph</a></li></ol></nav></body></html>
        """.trimIndent())
        val chapter = File(folder, "one.xhtml")
        chapter.writeText(chapter.readText().replace("</body>",
            """<p id="ending">This is the ending of the chapter.</p></body>"""))
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithContentDescription("Go to The final paragraph").performClick()
        compose.waitUntil(20_000) { store.position(book) > .5f }
        assertTrue(store.position(book) > .5f)
        assertEquals(0, store.chapter(book))
    }

    @Test fun pageTurnsCrossChaptersAndGoingBackReturnsToTheLastPage() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        reader()
        var steps = 0
        while (store.chapter(book) == 0 && steps++ < 30) turn(1)
        assertEquals(1, store.chapter(book))
        reader()
        turn(-1)
        assertEquals(0, store.chapter(book))
        assertTrue(store.position(book) > .9f)
    }

    @Test fun bookmarkStillMatchesItsTextAfterChangingFontSize() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        val first = store.anchor(book)
        turn(1)
        assertTrue(store.anchor(book) != first)
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Bookmark this place").performClick()
        val saved = store.bookmarks(book).single().anchor
        assertNotNull(saved)
        compose.onNodeWithContentDescription("Reading appearance").performClick()
        compose.onNodeWithText("A+").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Remove this bookmark").fetchSemanticsNodes()
                .any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        assertEquals(saved, store.anchor(book))
        assertEquals(saved, store.bookmarks(book).single().anchor)
    }

    @Test fun floatingControlsRemainPaintedWhileTurningIllustratedPages() {
        addIllustratedChapter(store.folder(book))
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        compose.showReaderControls()
        val close = compose.onNodeWithContentDescription("Close book", useUnmergedTree = true)
        val contents = compose.onNodeWithContentDescription("Contents", useUnmergedTree = true)
        val topBounds = close.getUnclippedBoundsInRoot()
        val bottomBounds = contents.getUnclippedBoundsInRoot()
        val topImage = close.captureToImage()
        val bottomImage = contents.captureToImage()
        repeat(8) {
            val page = reader()
            // Illustration-only pages carry no text anchor, so watch the saved position too.
            val previous = store.position(book) to store.anchor(book)
            compose.runOnIdle { page.turnPage(1) }
            compose.waitUntil(10_000) { (store.position(book) to store.anchor(book)) != previous }
            close.assertIsDisplayed()
            contents.assertIsDisplayed()
            assertEquals(topBounds, close.getUnclippedBoundsInRoot())
            assertEquals(bottomBounds, contents.getUnclippedBoundsInRoot())
            assertSimilarPixels(topImage, close.captureToImage())
            assertSimilarPixels(bottomImage, contents.captureToImage())
        }
    }

    private fun assertSimilarPixels(expected: ImageBitmap, actual: ImageBitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val before = expected.toPixelMap()
        val after = actual.toPixelMap()
        var difference = 0.0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val a = before[x, y]
            val b = after[x, y]
            difference += kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)
        }
        assertTrue("Reader controls were overpainted on an image page",
            difference / (expected.width * expected.height * 3) < .02)
    }
}
