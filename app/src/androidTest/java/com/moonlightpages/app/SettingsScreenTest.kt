package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "settings-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
    }

    @After fun cleanup() { folder.deleteRecursively() }

    @Test fun swipingAndEdgeTapsAreSeparateChoicesThatAreNeverBothOn() {
        assertTrue(store.swipeToTurn())
        assertFalse(store.tapToTurn())
        compose.setContent { MaterialTheme { SettingsScreen(store, onBack = {}) } }
        val swipe = compose.onNodeWithText("Swipe between pages")
        val edges = compose.onNodeWithText("Tap the screen edges")
        swipe.assertIsOn()
        edges.assertIsOff()
        // Choosing edge taps switches swiping off, so the two never share one gesture.
        edges.performClick()
        compose.runOnIdle { assertFalse(store.swipeToTurn()); assertTrue(store.tapToTurn()) }
        swipe.assertIsOff()
        edges.assertIsOn()
        // The only way left on cannot be switched off: pages must always be turnable.
        edges.performClick()
        compose.runOnIdle { assertTrue(store.tapToTurn()) }
        edges.assertIsOn()
        swipe.performClick()
        compose.runOnIdle { assertTrue(store.swipeToTurn()); assertFalse(store.tapToTurn()) }
        swipe.assertIsOn()
        edges.assertIsOff()
    }

    @Test fun keepingTheScreenOnStartsOffAndPersists() {
        assertFalse(store.keepAwake())
        compose.setContent { MaterialTheme { SettingsScreen(store, onBack = {}) } }
        val keepAwake = compose.onNodeWithText("Keep the screen on while reading")
        keepAwake.assertIsOff()
        keepAwake.performClick()
        compose.runOnIdle { assertTrue(store.keepAwake()) }
        keepAwake.assertIsOn()
    }

    /**
     * The theme is the reader's own choice: the switch opens on the state it was given and reports
     * every change. Nothing is saved by the screen itself, so the app stays the one place that
     * decides where a setting lives.
     */
    @Test fun theThemeIsTheReadersChoiceAndIsRemembered() {
        var chosen: Boolean? = null
        compose.setContent { MaterialTheme {
            SettingsScreen(store, onBack = {}, darkTheme = false, onDarkTheme = { chosen = it })
        } }
        val dark = compose.onNodeWithText("Dark theme").performScrollTo()
        dark.assertIsOff()
        dark.performClick()
        compose.runOnIdle { assertTrue(chosen == true) }
        dark.assertIsOn()
        dark.performClick()
        compose.runOnIdle { assertTrue(chosen == false) }
        dark.assertIsOff()
    }

    /** The theme is the reader's own choice, and it is kept where the app can find it again. */
    @Test fun theThemeIsKept() {
        store.saveDarkTheme(true)
        assertTrue(store.darkTheme())
        store.saveDarkTheme(false)
        assertFalse(store.darkTheme())
    }

    @Test fun backLeavesTheScreenAndPrivacyPromiseIsVisible() {        var back = false
        compose.setContent { MaterialTheme { SettingsScreen(store, onBack = { back = true }) } }
        compose.onNodeWithText("ABOUT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your books, progress, bookmarks, and highlights stay on this device and are never uploaded.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.runOnIdle { assertTrue(back) }
    }

    @Test fun aBackupAndARestoreAreOfferedAndRestoringAsksFirst() {
        var backingUp = false
        var restoring = false
        compose.setContent { MaterialTheme {
            SettingsScreen(store, onBack = {}, onBackup = { backingUp = true }, onRestore = { restoring = true })
        } }
        compose.onNodeWithText("Back up your library").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(backingUp) }
        // Restoring asks before a file is chosen, and says what it will and will not do.
        compose.onNodeWithText("Restore from a backup").performScrollTo().performClick()
        compose.onNodeWithText("Restore from a backup?").assertIsDisplayed()
        compose.onNodeWithText("Choose a file").performClick()
        compose.runOnIdle { assertTrue(restoring) }
    }

    @Test fun whileABackupIsBeingWrittenTheRowsAreBusyAndSaySo() {
        compose.setContent { MaterialTheme { SettingsScreen(store, onBack = {}, backingUp = true) } }
        compose.onNodeWithText("Saving your library…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back up your library").assertIsNotEnabled()
        compose.onNodeWithText("Restore from a backup").assertIsNotEnabled()
    }
}
