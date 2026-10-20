package com.moonlightpages.app

import org.junit.Assert.*
import org.junit.Test

class ChapterPlanTest {
    private fun plan(vararg sections: Int): ChapterPlan =
        ChapterPlan.of(sections.flatMapIndexed { section, count ->
            (0 until count).map { ChapterLink("Chapter ${section + 1}.$it", section, "c$section-$it") }
        }, sections.size)!!

    @Test fun tenChaptersInOneFileDivideItUnlikeTenFiles() {
        // Four files, ten chapters in the first: the bar must move by the chapter, not by the file.
        val plan = plan(10, 1, 1, 1)
        assertEquals(13, plan.total)
        assertNotEquals(0.25f, plan.progress(0, 0.5f), 0.001f)
        assertEquals(5f / 13f, plan.progress(0, 0.5f), 0.001f)
        assertEquals(10f / 13f, plan.progress(1, 0f), 0.001f)
        assertEquals(1f, plan.progress(3, 1f), 0.001f)
    }

    @Test fun oneChapterPerFileKeepsTheProgressTheOldCountGave() {
        val plan = plan(1, 1, 1, 1)
        assertEquals(0.25f, plan.progress(0, 1f), 0.001f)
        assertEquals(0.75f, plan.progress(2, 1f), 0.001f)
        assertEquals(0.875f, plan.progress(3, 0.5f), 0.001f)
    }

    @Test fun progressNeverLeavesTheBookOrJumpesBackwards() {
        val plan = plan(3, 2, 4)
        assertEquals(0f, plan.progress(-1, 0f), 0.001f)
        assertEquals(0f, plan.progress(0, -2f), 0.001f)
        assertEquals(1f, plan.progress(2, 4f), 0.001f)
        // The store always clamps the section it asks about, so a section past the end is nonsense
        // and must not be read as the end of the book.
        assertEquals(0f, plan.progress(9, 1f), 0.001f)
        var previous = -1f
        for (section in 0..2) for (step in 0..10) {
            val share = plan.progress(section, step / 10f)
            assertTrue("$section at $step went backwards", share >= previous)
            previous = share
        }
    }

    @Test fun aContentsThatLeavesSectionOutIsNotTrusted() {
        // A book whose index never mentions one of its files must fall back to counting files.
        assertNull(ChapterPlan.of(listOf(ChapterLink("One", 0, "c1")), 2))
        assertNull(ChapterPlan.of(emptyList(), 2))
        assertNull(ChapterPlan.of(listOf(ChapterLink("One", 0, "c1")), 0))
    }

    @Test fun theReaderIsToldWhichOfTheFilesChaptersItIsIn() {
        val marks = listOf(NamedChapter("First", 0), NamedChapter("Second", 12), NamedChapter("Third", 30))
        assertEquals("First", namedChapterAt(marks, 0)!!.title)
        assertEquals("First", namedChapterAt(marks, 11)!!.title)
        assertEquals("Second", namedChapterAt(marks, 12)!!.title)
        assertEquals("Third", namedChapterAt(marks, 400)!!.title)
    }

    @Test fun pagesLeftCountToTheNextChapterRatherThanTheEndOfTheFile() {
        val marks = listOf(NamedChapter("First", 0), NamedChapter("Second", 12), NamedChapter("Third", 30))
        assertEquals(11, pagesLeftInChapter(marks, 0))
        assertEquals(0, pagesLeftInChapter(marks, 11))
        assertEquals(17, pagesLeftInChapter(marks, 12))
        assertNull("The last chapter runs to the end of the file", pagesLeftInChapter(marks, 30))
    }

    @Test fun unplacedChaptersLeaveTheFileToSpeakForItself() {
        assertNull(namedChapterAt(emptyList(), 4))
        assertNull(pagesLeftInChapter(emptyList(), 4))
        val one = listOf(NamedChapter("Only", 0))
        assertEquals("Only", namedChapterAt(one, 9)!!.title)
        assertNull(pagesLeftInChapter(one, 9))
    }

    @Test fun thePageCountBelongsToTheChapterBeingReadRatherThanTheFile() {
        val marks = listOf(NamedChapter("First", 0), NamedChapter("Second", 12), NamedChapter("Third", 30))
        assertEquals(0 to 11, chapterPageSpan(marks, 0, 40))
        assertEquals(0 to 11, chapterPageSpan(marks, 5, 40))
        assertEquals(12 to 29, chapterPageSpan(marks, 12, 40))
        assertEquals(30 to 40, chapterPageSpan(marks, 30, 40))
        assertEquals("The last chapter runs to the end of the file", 30 to 40, chapterPageSpan(marks, 40, 40))
        assertNull("With no chapters placed, the file speaks for itself", chapterPageSpan(emptyList(), 4, 40))
    }
}
