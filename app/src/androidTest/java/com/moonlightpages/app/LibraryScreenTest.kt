package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.moonlightpages.app.ui.theme.MoonlightTheme
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore
    private lateinit var book: Book

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "library-screen-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
        book = sampleBook(store)
    }
    @After fun cleanup() { folder.deleteRecursively() }

    @Test fun searchHasClearAndNoResultsStates() {
        val other = book.copy(id = "other", title = "The Other Story", author = "Another Author", cover = null)
        compose.setContent { MoonlightTheme(darkTheme = false) {
            LibraryScreen(LibraryState(listOf(book, other), loading = false, available = true), store, {}, {}, {}, {})
        } }
        // The search field is asked for rather than always on the shelf, so the shelf stays quiet.
        compose.onNodeWithContentDescription("Search your library").performClick()
        compose.onNodeWithText("Search your library").performTextInput("  quiet moonlight  ")
        compose.onNodeWithText("1 of 2").assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Search your library").performTextInput("unknown title")
        compose.onNodeWithText("No books match your search.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show all books").performClick()
        compose.onNodeWithText("2 books").assertExists()
    }

    @Test fun holdingACoverOffersToDeleteTheBookAndKeepsItUntilConfirmed() {
        var deleted: Book? = null
        compose.setContent { MoonlightTheme(darkTheme = false) {
            LibraryScreen(LibraryState(listOf(book), loading = false, available = true), store,
                {}, {}, {}, {}, onDelete = { deleted = it })
        } }
        // The row's own menu says the same thing without needing a long press.
        compose.onNodeWithContentDescription("More for ${book.title}").performClick()
        compose.onNodeWithText("Remove from shelf").assertExists()
        compose.onNodeWithText("Remove from shelf").performClick()
        compose.onNodeWithText("Delete “${book.title}”?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep it").performClick()
        compose.runOnIdle { assertNull(deleted) }

        compose.onNodeWithContentDescription("${book.title}, by ${book.author}")
            .performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Delete “${book.title}”?", substring = true).assertIsDisplayed()

        // Keeping it must leave the book exactly where it was.
        compose.onNodeWithText("Keep it").performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithContentDescription("${book.title}, by ${book.author}").performScrollTo().assertExists()

        compose.onNodeWithContentDescription("${book.title}, by ${book.author}")
            .performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(book.id, deleted?.id) }
    }

    @Test fun theDeletePromptSaysTheImportedFileItselfIsNotTouched() {
        compose.setContent { MoonlightTheme(darkTheme = true) { DeleteBookDialog(book, onDelete = {}, onKeep = {}) } }
        compose.onNodeWithText("Delete “${book.title}”?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("stays on your phone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep it").assertIsDisplayed()
    }

    @Test fun searchAndShelfStateSurviveOpeningAndLeavingABook() {
        var opened by mutableStateOf(false)
        compose.setContent { MoonlightTheme(darkTheme = false) {
            val holder = rememberSaveableStateHolder()
            if (!opened) holder.SaveableStateProvider("library") {
                LibraryScreen(LibraryState(listOf(book), loading = false, available = true), store, {}, {}, {}, { opened = true })
            }
        } }
        compose.onNodeWithContentDescription("Search your library").performClick()
        compose.onNodeWithText("Search your library").performTextInput("quiet")
        compose.onNodeWithContentDescription("${book.title}, by ${book.author}").performScrollTo().performClick()
        compose.runOnIdle { opened = false }
        compose.onNodeWithText("Search your library").assertTextContains("quiet")
    }

    @Test fun emptyLibraryActionsAreReachableOnShortScreensAndDisabledDuringImport() {
        var importing by mutableStateOf(false)
        compose.setContent { MoonlightTheme(darkTheme = true) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                LibraryScreen(LibraryState(loading = false, importing = importing, available = true), store,
                    onImport = { importing = true }, onSample = {}, onRetry = {}, onOpen = {})
            }
        } }
        compose.onNodeWithText("+  Import your first book").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("+  Import your first book").assertIsNotEnabled()
        compose.onNodeWithText("Or start with Moby Dick").assertIsNotEnabled()
    }

    @Test fun theShelfDoesNotInviteAnImportWhileABackupIsBeingWritten() {
        compose.setContent { MoonlightTheme(darkTheme = false) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                LibraryScreen(LibraryState(loading = false, backingUp = true, available = true), store,
                    onImport = {}, onSample = {}, onRetry = {}, onOpen = {})
            }
        } }
        compose.onNodeWithText("+  Import your first book").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Or start with Moby Dick").assertIsNotEnabled()
    }

    @Test fun theImportActionIsNotTappableWhileABackupIsBeingWritten() {
        compose.setContent { MoonlightTheme(darkTheme = false) {
            LibraryScreen(LibraryState(listOf(book), loading = false, backingUp = true, available = true), store,
                {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("Import a book").assertIsNotEnabled()
    }

    @Test fun theBarChoosesAnotherRoom() {
        var chosen: ShelfTab? = null
        compose.setContent { MoonlightTheme(darkTheme = false) {
            ShelfBar(ShelfTab.Library) { chosen = it }
        } }
        compose.onNodeWithContentDescription("Settings").performClick()
        assertEquals(ShelfTab.Settings, chosen)
        compose.onNodeWithContentDescription("Notebook").performClick()
        assertEquals(ShelfTab.Notebook, chosen)
        compose.onNodeWithContentDescription("Library").assertIsSelected()
    }

    @Test fun coversShowProgressAndFinishedBooksAreFlagged() {
        val reading = book.copy(id = "reading", title = "Half Read")
        val done = book.copy(id = "done", title = "All Done")
        store.savePosition(reading, 0, .5f)
        store.savePosition(done, 1, 1f)
        compose.setContent { MoonlightTheme(darkTheme = true) {
            LibraryScreen(LibraryState(listOf(reading, done), loading = false, available = true), store,
                {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("Half Read, by Moonlight Pages").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("All Done, by Moonlight Pages, finished").performScrollTo().assertIsDisplayed()
    }

    @Test fun theResumeCardNamesTheBookAndHowFarInItIs() {
        val reading = book.copy(id = "reading", title = "Half Read")
        store.savePosition(reading, 0, .5f)
        var opened: Book? = null
        compose.setContent { MoonlightTheme(darkTheme = false) {
            LibraryScreen(LibraryState(listOf(reading), loading = false, available = true), store,
                {}, {}, {}, { opened = it })
        } }
        compose.onNodeWithContentDescription("Continue reading Half Read at 25%").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("reading", opened?.id) }
    }

    /** Long titles keep their second line under the cover instead of being clipped by it. */
    @Test fun longTitlesRenderFullyUnderTheirCovers() {
        val withCover = book.copy(id = "hailmary", title = "Project Hail Mary: A Novel",
            author = "Andy Weir", cover = "cover.jpg")
        val folder = store.folder(withCover).apply { mkdirs() }
        val image = android.graphics.Bitmap.createBitmap(1000, 1500, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        canvas.drawColor(android.graphics.Color.rgb(18, 20, 26))
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(226, 190, 60) }
        canvas.drawRect(0f, 0f, 1000f, 620f, paint)
        paint.color = android.graphics.Color.rgb(240, 240, 235)
        repeat(5) { canvas.drawRect(90f, 900f + it * 120, 910f, 950f + it * 120, paint) }
        File(folder, "cover.jpg").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }
        image.recycle()
        compose.setContent { MoonlightTheme(darkTheme = true) {
            LibraryScreen(LibraryState(listOf(withCover, book), loading = false, available = true), store,
                {}, {}, {}, {})
        } }
        // Cover decoding runs on Dispatchers.IO, which the test clock does not wait for.
        Thread.sleep(1500)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Project Hail Mary: A Novel, by Andy Weir")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("A Quiet Beginning, by Moonlight Pages").assertIsDisplayed()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "moonlight-night-shelf.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }

    @Test fun rendersPolishedShelfPreview() {
        val shelf = listOf(book, book.copy(id = "pines", title = "Between the Pines"),
            book.copy(id = "home", title = "The Long Way Home"), book.copy(id = "letters", title = "Letters at Dusk"))
        compose.setContent { MoonlightTheme(darkTheme = false) {
            LibraryScreen(LibraryState(shelf, loading = false, available = true), store, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Library").assertIsDisplayed()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "moonlight-library-preview.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }
}
