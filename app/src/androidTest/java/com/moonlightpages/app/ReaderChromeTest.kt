package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
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
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ReaderChromeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    private lateinit var book: Book

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "reader-chrome-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
        book = sampleBook(store)
        // A long chapter gives several pages, so "pages left" and "n of m" are both meaningful.
        val chapter = File(store.folder(book), "one.xhtml")
        chapter.writeText(chapter.readText().replace("</body>", (1..30).joinToString("") {
            "<p>Paragraph $it. A lamp by the window lights the page. Every sentence has its own place, even as the shape of the page changes.</p>"
        } + "</body>"))
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

    /** Top edge of the reading page in screen pixels. */
    private fun readerTop(): Int {
        var top = -1
        onView(isAssignableFrom(BookWebView::class.java)).perform(object : ViewAction {
            override fun getDescription() = "Locate the reading page"
            override fun getConstraints() = isAssignableFrom(BookWebView::class.java)
            override fun perform(controller: UiController, view: View) {
                top = IntArray(2).also { view.getLocationOnScreen(it) }[1]
            }
        })
        return top
    }

    @Test fun pageHasNoBarsAndShowsChapterProgressAndFourFloatingActions() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        compose.showReaderControls()
        // The old toolbar and footer are gone. The reading page fills the screen instead.
        compose.onNodeWithText("‹ Library").assertDoesNotExist()
        compose.onNodeWithText("Previous").assertDoesNotExist()
        compose.onNodeWithText("Next", substring = false).assertDoesNotExist()
        compose.onNodeWithContentDescription("Close book").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bookmark this place").assertIsDisplayed()
        compose.onNodeWithContentDescription("Contents").assertIsDisplayed()
        compose.onNodeWithText("Aa").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bookmarks").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search in book").assertIsDisplayed()
        compose.onNodeWithText("pages left in this chapter", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 of ", substring = true).assertIsDisplayed()
    }

    @Test fun theControlsStayHiddenUntilTheMiddleOfThePageIsTapped() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        // Reading is the page: nothing else is on screen until the reader asks for it.
        compose.onNodeWithContentDescription("Close book").assertDoesNotExist()
        compose.onNodeWithContentDescription("Contents").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search in book").assertDoesNotExist()
        compose.onNodeWithText("pages left in this chapter", substring = true).assertDoesNotExist()
        compose.onNodeWithText("1 of ", substring = true).assertDoesNotExist()
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Close book").assertIsDisplayed()
        compose.onNodeWithContentDescription("Contents").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search in book").assertIsDisplayed()
        compose.onNodeWithText("pages left in this chapter", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 of ", substring = true).assertIsDisplayed()
        // The same tap puts them away again.
        compose.tapTheMiddleOfThePage()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Close book").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("Contents").assertDoesNotExist()
    }

    @Test fun theReadingPageIsInsetSoNothingHidesBehindTheFloatingControls() {
        var left = false
        compose.setContent { MaterialTheme { ReaderScreen(book, store) { left = true } } }
        val page = reader()
        compose.showReaderControls()
        val full = compose.onRoot().fetchSemanticsNode().size.height
        val top = readerTop()
        assertTrue("The page must start below the floating hint and buttons", top > 0)
        assertTrue("The page must end above the page number and toolbar", top + page.height < full)
        // Opening and closing a surface must not rebuild or resize the reading page.
        compose.onNodeWithContentDescription("Search in book").performClick()
        compose.onNodeWithText("Search this book").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithText("Search this book").assertDoesNotExist()
        assertSame(page, reader())
        assertEquals(top, readerTop())
        compose.onNodeWithContentDescription("Close book").performClick()
        assertTrue(left)
    }

    @Test fun searchesEveryChapterAndReopensTheExactPassage() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Search in book").performClick()
        compose.onNodeWithText("Word or phrase").performTextInput("particular kind of quiet")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Open result in", substring = true)
                .fetchSemanticsNodes().size == 2
        }
        compose.onNodeWithText("2 matches").assertIsDisplayed()
        // Results are grouped by chapter, so the second one belongs to the second chapter.
        compose.onAllNodesWithContentDescription("Open result in", substring = true)[1].performClick()
        compose.waitUntil(20_000) { store.chapter(book) == 1 && store.anchor(book) != null }
        assertEquals(1, store.chapter(book))
        // The reopened anchor must point at the matched words, not merely somewhere in the chapter.
        assertTrue(store.anchor(book)!!.quote.startsWith("particular kind of quiet"))
    }

    @Test fun searchResultsSayWhichChapterTheMatchIsIn() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        awaitReader()
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Search in book").performClick()
        compose.onNodeWithText("Word or phrase").performTextInput("particular kind of quiet")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Open result in", substring = true)
                .fetchSemanticsNodes().size == 2
        }
        // Each match names the chapter it was found in, taken from that chapter's own heading rather
        // than from the file it happens to be stored in.
        compose.onNodeWithContentDescription("Open result in A Quiet Beginning", substring = true).assertExists()
        compose.onNodeWithContentDescription("Open result in One More Page", substring = true).assertExists()
    }

    @Test fun searchSaysSoWhenNothingMatchesAndKeepsThePageReadable() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val height = page.height
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Search in book").performClick()
        compose.onNodeWithText("Word or phrase").performTextInput("excelsior")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("No matches for", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No matches for “excelsior”.", substring = false).assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Type at least two letters to look through this book.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close search").performClick()
        assertSame(page, reader())
        assertEquals(height, page.height)
    }

    @Test fun rendersReaderPreview() {
        store.saveAppearance(20, 0)
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        compose.showReaderControls()
        compose.runOnIdle { page.turnPage(1) }
        compose.waitUntil(10_000) { store.position(book) > 0f }
        compose.waitForIdle()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "moonlight-reader-preview.png")
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(output.length() > 0)
    }

    @Test fun appearanceSheetOpensFromThePillWithoutReloadingThePage() {        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val height = page.height
        compose.showReaderControls()
        compose.onNodeWithContentDescription("Reading appearance").performClick()
        compose.onNodeWithText("Reading appearance", substring = false).assertExists()
        compose.onNodeWithText("Sepia").performClick()
        compose.onNodeWithText("Done").performClick()
        assertSame(page, reader())
        assertEquals(height, page.height)
        assertEquals(1, store.paper())
    }
}
