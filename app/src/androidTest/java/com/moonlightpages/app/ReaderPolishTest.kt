package com.moonlightpages.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ReaderPolishTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    private lateinit var book: Book

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "reader-polish-test-${UUID.randomUUID()}"
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
            override fun getDescription() = "Get the test reader"
            override fun getConstraints() = isAssignableFrom(BookWebView::class.java)
            override fun perform(controller: UiController, view: View) { found = view as BookWebView }
        })
        return found!!
    }

    @Test fun appearanceSheetDoesNotResizeOrReloadTheReadingPage() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val before = reader()
        val height = before.height
        compose.showReaderControls()
        compose.onNodeWithText("Aa").performClick()
        compose.onNodeWithText("Reading appearance", substring = false).assertExists()
        assertEquals(height, before.height)
        compose.onNodeWithText("A+").performClick()
        compose.onNodeWithText("Done").performClick()
        assertSame(before, reader())
        assertEquals(height, before.height)
        assertEquals(22, store.fontSize())
        assertNotNull(store.anchor(book))
    }

    /** Line spacing, margins and typeface are all offered, and all reach the settings. */
    @Test fun theAppearanceSheetSetsSpacingMarginsAndTypeface() {
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        compose.showReaderControls()
        compose.onNodeWithText("Aa").performClick()
        compose.onNodeWithText("Line spacing").assertExists()
        compose.onNodeWithText("Roomy").performScrollTo().performClick()
        compose.onNodeWithText("Narrow").performScrollTo().performClick()
        compose.onNodeWithText("Sans").performScrollTo().performClick()
        compose.onNodeWithText("Night dimming").performScrollTo().assertExists()
        compose.onNodeWithText("40%").performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle {
            assertEquals(2, store.lineSpacing())
            assertEquals(0, store.pageInset())
            assertEquals(1, store.pageFont())
            assertEquals(40, store.nightDim())
        }
        // The same reader, laid out again: choosing a page's shape must never reload the chapter.
        assertSame(page, reader())
    }

    /**
     * Night dimming takes the phone's own brightness down rather than laying a film over the page.
     * A film over the page would end up inside the copy a page turn holds of it, so the page being
     * left would be dimmed twice over while the page arriving was dimmed once.
     */
    @Test fun nightDimmingTakesTheBrightnessDownAndLeavesThePageItselfAlone() {
        store.saveNightDim(0)
        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val page = reader()
        val activity = activityOf(page)
        compose.runOnIdle {
            assertEquals("With no dimming the phone's brightness is left alone",
                -1f, activity.window.attributes.screenBrightness, 0.001f)
        }
        compose.showReaderControls()
        compose.onNodeWithText("Aa").performClick()
        compose.onNodeWithText("60%").performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(60, store.nightDim())
            val taken = activity.window.attributes.screenBrightness
            assertTrue("The screen must be taken below its own setting, but not to nothing: $taken",
                taken in MIN_READING_BRIGHTNESS..0.6f)
        }
    }

    /** The activity this reader is shown in. */
    private fun activityOf(view: View): Activity {
        var context: Context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        return context as Activity
    }

    @Test fun nightPaperAlsoDarkensAppearanceAndContentsAndSystemBarIcons() {        compose.setContent { MaterialTheme { ReaderScreen(book, store) {} } }
        val before = reader()
        compose.showReaderControls()
        compose.onNodeWithText("Aa").performClick()
        compose.onNodeWithText("Night", substring = false).performClick()
        val appearance = compose.onNodeWithText("Reading appearance", useUnmergedTree = true).captureToImage().toPixelMap()[0, 0]
        assertTrue(appearance.red + appearance.green + appearance.blue < 1.4f)
        compose.onNodeWithText("Done").performClick()
        assertSame(before, reader())
        val activity = activityOf(before)
        compose.runOnIdle {
            val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse(bars.isAppearanceLightStatusBars)
            assertFalse(bars.isAppearanceLightNavigationBars)
        }
        compose.onNodeWithContentDescription("Contents").performClick()
        val title = compose.onAllNodesWithText(book.title, useUnmergedTree = true)
            .fetchSemanticsNodes().last()
        val bounds = title.boundsInRoot
        assertTrue(bounds.width > 0)
        // Material's night surface should also apply to the chapter sheet's text background.
        val chapters = compose.onNodeWithText("Chapters", useUnmergedTree = true).captureToImage().toPixelMap()[0, 0]
        assertTrue(chapters.red + chapters.green + chapters.blue < 1.4f)
    }
}
