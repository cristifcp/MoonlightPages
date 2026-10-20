package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionAtTest {
    private val sections = listOf(0 to "Opening", 5 to "The Long Road", 9 to "Afterword")

    @Test fun aNodeNamesTheSectionThatBeginsAtOrBeforeIt() {
        assertEquals("Opening", sectionAt(sections, 0))
        assertEquals("Opening", sectionAt(sections, 4))
        assertEquals("The Long Road", sectionAt(sections, 5))
        assertEquals("The Long Road", sectionAt(sections, 8))
        assertEquals("Afterword", sectionAt(sections, 40))
    }

    @Test fun aFileWithNoSectionsNamesNothing() {
        assertNull(sectionAt(emptyList(), 3))
    }

    @Test fun aNodeBeforeTheFirstSectionNamesNothing() {
        assertNull(sectionAt(listOf(5 to "Later"), 2))
    }
}
