package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape of a page turn: how the arriving page travels, and what becomes of the page it covers. */
class PageTurnGeometryTest {
    /** Forward, the arriving page sweeps in from the right; going back it comes from the left. */
    @Test fun theArrivingPageCoversFromTheRightAndThenFromTheLeft() {
        assertEquals(1000f, pageTurnEdge(1000f, 1, 0f), 0.01f)
        assertEquals(500f, pageTurnEdge(1000f, 1, 0.5f), 0.01f)
        assertEquals(0f, pageTurnEdge(1000f, 1, 1f), 0.01f)
        assertEquals(0f, pageTurnEdge(1000f, -1, 0f), 0.01f)
        assertEquals(500f, pageTurnEdge(1000f, -1, 0.5f), 0.01f)
        assertEquals(1000f, pageTurnEdge(1000f, -1, 1f), 0.01f)
    }

    /** The page arriving sets off a whole page away, on the far side of the screen, and lands flat. */
    @Test fun theArrivingPageTravelsAWholePageInFromTheFarSide() {
        assertEquals(1000f, pageTurnTravel(1000f, 1, 0f), 0.01f)
        assertEquals(500f, pageTurnTravel(1000f, 1, 0.5f), 0.01f)
        assertEquals(0f, pageTurnTravel(1000f, 1, 1f), 0.01f)
        assertEquals(-1000f, pageTurnTravel(1000f, -1, 0f), 0.01f)
        assertEquals(-500f, pageTurnTravel(1000f, -1, 0.5f), 0.01f)
        assertEquals(0f, pageTurnTravel(1000f, -1, 1f), 0.01f)
        assertEquals("Past the end of the turn, the page arriving is already where it belongs",
            0f, pageTurnTravel(1000f, 1, 2f), 0.01f)
        // Its leading edge is one and the same thing as the distance it still has to travel.
        for (step in 0..10) {
            val progress = step / 10f
            assertEquals(pageTurnEdge(1000f, 1, progress), pageTurnTravel(1000f, 1, progress), 0.01f)
        }
    }

    /** The page left behind is shoved aside early, and dims the whole way through. */
    @Test fun thePageLeftBehindIsShovedAsideAndDarkens() {
        assertEquals(0f, pageTurnNudge(1000f, 0f), 0.01f)
        assertEquals(1000f * NUDGE_SHARE, pageTurnNudge(1000f, 1f), 0.01f)
        assertTrue(pageTurnNudge(1000f, 0.5f) > pageTurnNudge(1000f, 0f))
        assertTrue(pageTurnNudge(1000f, 0.5f) < pageTurnNudge(1000f, 1f))
        assertTrue("Most of the shove is over well before the page is covered",
            pageTurnNudge(1000f, 0.4f) > 1000f * NUDGE_SHARE * 0.6f)
        assertEquals(1000f * NUDGE_SHARE, pageTurnNudge(1000f, 2f), 0.01f)

        assertEquals("A page is not darkened before the turn even begins", 0f, pageTurnVeil(0f), 0.0001f)
        assertEquals(VEIL_END, pageTurnVeil(1f), 0.0001f)
        assertTrue(pageTurnVeil(0.5f) > pageTurnVeil(0f))
        assertTrue(pageTurnVeil(0.5f) < pageTurnVeil(1f))
        assertEquals(0f, pageTurnVeil(-1f), 0.0001f)
        assertEquals(VEIL_END, pageTurnVeil(2f), 0.0001f)
    }

    /** A turn has to read as a page landing: quick enough to feel, slow enough to be seen. */
    @Test fun aTurnTakesAMomentAndLeavesASoftEdge() {
        assertTrue("A turn of $TURN_MILLIS ms is not a page landing", TURN_MILLIS in 250..450)
        assertTrue("The page left behind is shoved aside, not thrown off the screen",
            NUDGE_SHARE in 0.05f..0.25f)
        assertTrue("The shadow of the arriving edge is neither a hard line nor the whole page",
            FOLD_SHARE in 0.05f..0.25f)
        assertTrue(FOLD_ALPHA > 0f && FOLD_ALPHA < VEIL_END)
        assertTrue("A page must not turn for a touch that barely moved",
            SWIPE_TURN_SHARE in 0.08f..0.3f)
        assertTrue("A flick is quicker than a swipe that has only just been carried over",
            SWIPE_FLING > 0f)
    }

    /** A swipe turns the page once it has gone far enough across, or when it was a flick. */
    @Test fun aSwipeTurnsThePageWhenItHasGoneFarEnoughOrFlicked() {
        assertFalse("A touch that has not moved turns nothing", swipeCompletes(0f, 0f))
        assertFalse(swipeCompletes(SWIPE_TURN_SHARE - 0.05f, 0f))
        assertTrue("Swipe most of the way across is enough", swipeCompletes(SWIPE_TURN_SHARE, 0f))
        assertTrue("A flick turns however short it was", swipeCompletes(0.1f, SWIPE_FLING))
        assertFalse("A swipe the other way is not a flick forwards", swipeCompletes(0.1f, -SWIPE_FLING))
        assertFalse("A slow swipe that has not gone far enough turns nothing",
            swipeCompletes(0.1f, SWIPE_FLING - 0.1f))
    }
}
