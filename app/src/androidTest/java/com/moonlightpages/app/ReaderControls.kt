package com.moonlightpages.app

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom

/**
 * The reader keeps its controls out of the way until the middle of the page is tapped, so a test
 * that needs them asks for them. Only Espresso can deliver a tap to a WebView at a chosen point.
 */
internal fun ComposeContentTestRule.tapTheMiddleOfThePage() {
    onView(isAssignableFrom(BookWebView::class.java)).perform(GeneralClickAction(Tap.SINGLE,
        CoordinatesProvider { page -> floatArrayOf(page.width * .5f, page.height * .5f) },
        Press.FINGER, InputDevice.SOURCE_UNKNOWN, MotionEvent.BUTTON_PRIMARY))
    waitForIdle()
}

/** Taps the middle of the page and waits for the reading controls to fade in. */
internal fun ComposeContentTestRule.showReaderControls() {
    if (onAllNodesWithContentDescription("Close book").fetchSemanticsNodes().isEmpty()) {
        tapTheMiddleOfThePage()
    }
    waitUntil(10_000) {
        onAllNodesWithContentDescription("Close book").fetchSemanticsNodes().isNotEmpty()
    }
}

/** The page is ready once the opening spinner has gone; the controls may still be hidden. */
internal fun ComposeContentTestRule.awaitReadingPage() {
    waitUntil(20_000) { onAllNodesWithText("Opening chapter…").fetchSemanticsNodes().isEmpty() }
}
