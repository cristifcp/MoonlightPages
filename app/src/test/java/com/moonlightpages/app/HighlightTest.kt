package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a marked passage is named, and how much of a chapter name is kept. */
class HighlightTest {
    private val passage = SelectedPassage(3, TextAnchor(1, 13, "particular"), TextAnchor(1, 23),
        "particular", .2f)

    @Test fun aMarkedPassageReadsByTheBooksOwnChapterName() {
        assertEquals("CHAPTER 20. All Astir.", passageChapter(passage.copy(title = "CHAPTER 20. All Astir.")))
    }

    @Test fun aChapterTheBookDoesNotNameReadsByItsPlaceInTheFile() {
        assertEquals("Section 4", passageChapter(passage))
        assertEquals("Section 4", passageChapter(passage.copy(title = "   ")))
    }

    @Test fun aChapterNameIsTrimmedAndKeptShortEnoughToList() {
        assertEquals("Chapter One", cleanChapterName("  Chapter One  "))
        assertTrue(cleanChapterName("very long ".repeat(100)).length <= 120)
    }

    @Test fun aNoteIsTrimmedAndKeptShortEnoughToReadBack() {
        assertEquals("A thought.", cleanNote("  A thought.  "))
        assertTrue(cleanNote("thought ".repeat(500)).length <= 2000)
    }
}
