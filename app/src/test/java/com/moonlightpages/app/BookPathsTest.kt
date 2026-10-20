package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookPathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun normalizesInternalParentLinks() {
        assertEquals("Images/cover.jpg", resolveBookPath(temporary.root,
            "Text/package.opf", "../Images/cover.jpg"))
    }

    @Test fun decodesSpacesAndIgnoresFragments() {
        assertEquals("Text/chapter one.xhtml", resolveBookPath(temporary.root,
            "Text/package.opf", "chapter%20one.xhtml#start"))
    }

    @Test fun acceptsUnescapedSpaces() {
        assertEquals("chapter one.xhtml", resolveBookPath(temporary.root,
            "package.opf", "chapter one.xhtml"))
    }

    @Test fun fragmentOnlyLinkResolvesToSameDocument() {
        assertEquals("Text/nav.xhtml", resolveBookPath(temporary.root, "Text/nav.xhtml", "#chapter"))
    }

    @Test fun rejectsZipTraversal() {
        assertThrows(IllegalArgumentException::class.java) { bookFile(temporary.root, "../escape") }
    }

    @Test fun rejectsEncodedTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveBookPath(temporary.root, "book.opf", "%2e%2e/escape")
        }
    }

    @Test fun rejectsExternalResources() {
        listOf("https://example.com/book", "file:///private/book", "//example.com/book").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                resolveBookPath(temporary.root, "package.opf", it)
            }
        }
    }

    @Test fun rejectsAbsolutePaths() {
        assertThrows(IllegalArgumentException::class.java) { bookFile(temporary.root, "/escape") }
        assertThrows(IllegalArgumentException::class.java) {
            resolveBookPath(temporary.root, "Text/package.opf", "/escape")
        }
    }

    @Test fun readsWindowsSeparatorsAsOrdinarySeparators() {
        // Zips made by Windows tools use backslashes, and such a book still has to open.
        assertEquals(bookFile(temporary.root, "META-INF/container.xml"),
            bookFile(temporary.root, "META-INF\\container.xml"))
    }

    @Test fun rejectsWindowsStyleTraversal() {
        assertThrows(IllegalArgumentException::class.java) { bookFile(temporary.root, "..\\escape") }
    }

    @Test fun doesNotConfuseSiblingDirectoryWithRoot() {
        assertThrows(IllegalArgumentException::class.java) {
            bookFile(temporary.root, "../${temporary.root.name}-other/book.xhtml")
        }
    }
}
