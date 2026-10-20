package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** A page turn travels the arriving page over the page being left, instead of swapping the two. */
class PageTurnTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    private lateinit var book: Book

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "page-turn-test-${UUID.randomUUID()}"
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

    private fun reader(): BookWebView {
        compose.awaitReadingPage()
        var found: BookWebView? = null
        onView(isAssignableFrom(BookWebView::class.java)).perform(object : ViewAction {
            override fun getDescription() = "Get the reading page"
            override fun getConstraints() = isAssignableFrom(BookWebView::class.java)
            override fun perform(controller: UiController, view: View) { found = view as BookWebView }
        })
        return found!!
    }

    /**
     * Turns the page and waits for the screen to hand back the page being left. The copy is read
     * off the screen rather than drawn by the page itself, so it takes a frame to come back; the
     * turn proper begins the moment it does.
     */
    private fun turn(page: BookWebView, direction: Int) {
        val before = page.copies
        compose.runOnUiThread { page.turnPage(direction) }
        compose.waitUntil(10_000) { page.copies > before }
    }

    /** The page being left behind stays on screen, veiled, until the new one has covered it. */
    @Test fun thePageBeingLeftIsHeldWhileTheNextOneIsLaidOverIt() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val before = store.anchor(book)
        compose.mainClock.autoAdvance = false
        try {
            turn(page, 1)
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag(PAGE_TURN_TAG).assertExists()
            // The turn still ends: the layer goes once the arriving page has covered the last one.
            compose.mainClock.advanceTimeBy(TURN_MILLIS * 2L)
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag(PAGE_TURN_TAG).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { store.anchor(book) != before }
    }

    /** The turn covers the screen, not only the panel the page itself sits in. */
    @Test fun theTurnCoversTheWholeScreen() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        compose.mainClock.autoAdvance = false
        try {
            turn(page, 1)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            val layer = compose.onNodeWithTag(PAGE_TURN_TAG).getBoundsInRoot()
            val screen = compose.onRoot().getBoundsInRoot()
            val screenHeight = screen.bottom - screen.top
            assertTrue("The page sits inside the screen, so there is something for the turn to reach",
                page.height + 1f < with(compose.density) { screenHeight.toPx() })
            assertEquals("The turn reaches the top of the screen", screen.top.value, layer.top.value, 1f)
            assertEquals("and the bottom of it", screen.bottom.value, layer.bottom.value, 1f)
            assertEquals(screen.left.value, layer.left.value, 1f)
            assertEquals(screen.right.value, layer.right.value, 1f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** The page arriving travels in from off the screen: a whole page width, and then it is home. */
    @Test fun thePageArrivingTravelsInFromTheFarSideOfTheScreen() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        compose.mainClock.autoAdvance = false
        try {
            turn(page, 1)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            val settingOff = compose.runOnUiThread { page.translationX }
            assertTrue("The page arriving starts a page away, instead of being drawn where it lands",
                settingOff > page.width * 0.9f)
            compose.mainClock.advanceTimeBy(TURN_MILLIS / 2L)
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            val halfway = compose.runOnUiThread { page.translationX }
            assertTrue("Halfway through, the page arriving is still on its way over",
                halfway > 0f && halfway < settingOff)
            compose.mainClock.advanceTimeBy(TURN_MILLIS * 2L)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            assertEquals("A turn ends with the page arriving exactly where it belongs",
                0f, compose.runOnUiThread { page.translationX }, 0.5f)
            compose.onNodeWithTag(PAGE_TURN_TAG).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** Going back brings the page in from the other side of the screen, and it still lands. */
    @Test fun goingBackBringsThePageInFromTheOtherSide() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        // A turn back has to have somewhere to go back to, so read on a page first.
        val before = store.anchor(book)
        turn(page, 1)
        compose.waitUntil(10_000) { store.anchor(book) != before }
        compose.mainClock.advanceTimeBy(TURN_MILLIS * 3L)
        compose.mainClock.autoAdvance = false
        try {
            turn(page, -1)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            val settingOff = compose.runOnUiThread { page.translationX }
            assertTrue("Going back, the page arriving starts off the other side of the screen",
                settingOff < -page.width * 0.9f)
            compose.mainClock.advanceTimeBy(TURN_MILLIS * 2L)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            assertEquals("The turn back ends where it belongs as well",
                0f, compose.runOnUiThread { page.translationX }, 0.5f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** The page held for a turn is the page itself: the reader's own paper with its own ink on it. */
    @Test fun theHeldPageIsACopyOfThePageItself() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val held = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        compose.runOnUiThread { page.copyOfPage { held.set(it) } }
        compose.waitUntil(10_000) { held.get() != null }
        val copy = held.get()
        assertNotNull("A page that can be read must be able to be held", copy)
        assertEquals(page.width, copy!!.width)
        assertEquals(page.height, copy.height)
        assertEquals("The held page is drawn on the paper it was read on",
            android.graphics.Color.parseColor(papers[store.paper()].background), copy.getPixel(0, 0))
        val colours = HashSet<Int>()
        for (y in 0 until copy.height step (copy.height / 40).coerceAtLeast(1)) {
            for (x in 0 until copy.width step (copy.width / 20).coerceAtLeast(1)) colours.add(copy.getPixel(x, y))
        }
        assertTrue("The held page must carry the words it was drawn from", colours.size > 1)
    }

    /** Swipes a finger across the page: down, across to [toX], and up again. */
    private fun swipe(fromX: Float, toX: Float, stepMillis: Long = 20) {
        onView(isAssignableFrom(BookWebView::class.java)).perform(object : ViewAction {
            override fun getDescription() = "Swipe across the page"
            override fun getConstraints() = isAssignableFrom(BookWebView::class.java)
            override fun perform(controller: UiController, view: View) {
                fun send(at: Long, action: Int, x: Float) {
                    val event = MotionEvent.obtain(0, at, action, x, 900f, 0)
                    view.dispatchTouchEvent(event)
                    event.recycle()
                }
                send(0, MotionEvent.ACTION_DOWN, fromX)
                var at = 0L
                for (step in 1..8) {
                    at += stepMillis
                    send(at, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / 8f)
                    controller.loopMainThreadForAtLeast(stepMillis)
                }
                at += stepMillis
                send(at, MotionEvent.ACTION_UP, toX)
            }
        })
    }

    /** A swipe gets the very same turn an edge tap gets: nothing travels with the finger. */
    @Test fun aSwipeTurnsThePageTheSameWayAnEdgeTapDoes() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val before = store.anchor(book)
        compose.mainClock.autoAdvance = false
        try {
            val copies = page.copies
            swipe(900f, 300f)
            // Nothing has moved with the finger: the page waits for the turn to be let go.
            assertEquals("Swipe, and the page stays put until the finger is lifted",
                0f, compose.runOnUiThread { page.translationX }, 0.5f)
            compose.waitUntil(10_000) { page.copies > copies }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            val settingOff = compose.runOnUiThread { page.translationX }
            assertTrue("The page arriving starts a whole page away, exactly as it does after a tap",
                settingOff > page.width * 0.9f)
            compose.onNodeWithTag(PAGE_TURN_TAG).assertExists()
            compose.mainClock.advanceTimeBy(TURN_MILLIS * 3L)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag(PAGE_TURN_TAG).assertDoesNotExist()
            assertEquals("The turn lands the page where it belongs",
                0f, compose.runOnUiThread { page.translationX }, 0.5f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { store.anchor(book) != before }
    }

    /** A fifth of the page across is enough: a page should not have to be dragged half way over. */
    @Test fun aShortSlowSwipeTurnsThePage() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val before = store.anchor(book)
        compose.mainClock.autoAdvance = false
        try {
            val copies = page.copies
            // Deliberately unhurried, so it is the distance and not the speed that turns the page.
            swipe(page.width * 0.6f, page.width * 0.4f, stepMillis = 150)
            compose.waitUntil(10_000) { page.copies > copies }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.mainClock.advanceTimeBy(TURN_MILLIS * 3L)
        compose.waitUntil(10_000) { store.anchor(book) != before }
    }

    /** A quick flick turns the page even though it barely moved: the finger's speed is enough. */
    @Test fun aQuickFlickTurnsThePageWithoutGoingFar() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val before = store.anchor(book)
        compose.mainClock.autoAdvance = false
        try {
            val copies = page.copies
            swipe(page.width * 0.6f, page.width * 0.55f, stepMillis = 8)
            compose.waitUntil(10_000) { page.copies > copies }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.mainClock.advanceTimeBy(TURN_MILLIS * 3L)
        compose.waitUntil(10_000) { store.anchor(book) != before }
    }

    /** A swipe that went nowhere near far enough, and was slow, turns nothing at all. */
    @Test fun aShortSwipeTurnsNothing() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val before = store.anchor(book)
        val copies = page.copies
        swipe(700f, 640f, stepMillis = 120)
        // Long enough for a turn to have been asked for and its copy to have come back, if it had.
        Thread.sleep(300)
        compose.onNodeWithTag(PAGE_TURN_TAG).assertDoesNotExist()
        assertEquals("Nothing turned, so no page was held to turn over", copies, page.copies)
        assertEquals(before, store.anchor(book))
    }

    /** The page held for a turn sits exactly where that page sat: nothing on it jumps as a turn starts. */
    @Test fun theHeldPageSitsWhereThePageSat() {        store.saveAppearance(20, 0)
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val settled = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.mainClock.autoAdvance = false
        try {
            turn(page, 1)
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            val turning = compose.onRoot().captureToImage().asAndroidBitmap()
            val before = firstTextRow(settled)
            val now = firstTextRow(turning)
            assertTrue("The page has words to go by", before > 0 && now > 0)
            assertEquals("The page held for the turn must sit exactly where the page sat",
                before.toFloat(), now.toFloat(), 4f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** The first row of a whole frame carrying text, read across the page's own words. */
    private fun firstTextRow(image: Bitmap): Int {
        val from = image.width / 10
        val to = image.width - from
        for (y in 0 until image.height) {
            var x = from
            while (x < to) {
                val c = image.getPixel(x, y)
                if (0.299f * android.graphics.Color.red(c) +
                    0.587f * android.graphics.Color.green(c) +
                    0.114f * android.graphics.Color.blue(c) < 165f) return y
                x += 3
            }
        }
        return -1
    }

    /** Saves a filmstrip of one turn, four frames taken across it, for looking at by eye. */
    @Test fun rendersPageTurnPreview() {
        store.saveAppearance(20, 0)
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        compose.mainClock.autoAdvance = false
        try {
            turn(page, 1)
            val shots = mutableListOf<Bitmap>()
            var taken = 0
            for (stop in listOf(3, 8, 13, 18)) {
                repeat(stop - taken) { compose.mainClock.advanceTimeByFrame() }
                taken = stop
                shots += compose.onRoot().captureToImage().asAndroidBitmap()
            }
            val width = shots.first().width / 2
            val height = shots.first().height / 2
            val strip = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(strip)
            shots.forEachIndexed { index, shot ->
                val small = Bitmap.createScaledBitmap(shot, width, height, true)
                canvas.drawBitmap(small, (index % 2) * width.toFloat(), (index / 2) * height.toFloat(), null)
                small.recycle()
            }
            val output = File(InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null), "moonlight-page-turn.png")
            output.outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(output.length() > 0)
            // The turn still ends: the layer goes once the page arriving has covered the last one.
            compose.mainClock.advanceTimeBy(TURN_MILLIS * 2L)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag(PAGE_TURN_TAG).assertDoesNotExist()
            assertEquals(0f, compose.runOnUiThread { page.translationX }, 0.5f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
