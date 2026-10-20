package com.moonlightpages.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moonlightpages.app.ui.theme.MoonlightTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The prompt shown when a book handed to the app is already on the shelf. */
class IncomingDialogTest {
    @get:Rule val compose = createComposeRule()
    private val staged = Book("staged", "Metro 2033", "Dmitry A Glukhovsky", listOf("one.xhtml"))
    private val existing = Book("shelf", "Metro 2033", "Dmitry A Glukhovsky", listOf("one.xhtml"))

    @Test fun thePromptNamesTheBookAndOffersBothChoices() {
        compose.setContent {
            MoonlightTheme { DuplicateBookDialog(Duplicate(staged, existing), {}, {}, {}) }
        }
        compose.onNodeWithText("Already on your shelf").assertIsDisplayed()
        compose.onNodeWithText("Metro 2033", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Open my copy").assertIsDisplayed()
        compose.onNodeWithText("Add a second copy").assertIsDisplayed()
    }

    @Test fun eachChoiceIsReportedBack() {
        var opened = false
        var added = false
        compose.setContent {
            MoonlightTheme {
                DuplicateBookDialog(Duplicate(staged, existing),
                    onOpenExisting = { opened = true }, onAddCopy = { added = true }, onCancel = {})
            }
        }
        compose.onNodeWithText("Add a second copy").performClick()
        compose.runOnIdle { assertTrue(added); assertFalse(opened) }
        opened = false
        added = false
        compose.onNodeWithText("Open my copy").performClick()
        compose.runOnIdle { assertTrue(opened); assertFalse(added) }
    }
}
