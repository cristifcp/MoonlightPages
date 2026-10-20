package com.moonlightpages.app

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ChapterTextTest {
    private val source = """
        <html><body><h1>Heading</h1><p>Plain text.</p><p>A <em>split</em> paragraph.</p><p>   </p><p>Entity &amp; dash — ok</p><div>Nested <span><b>deep</b></span> text</div></body></html>
    """.trimIndent()

    @Test fun textNodesFollowDocumentOrderAndSkipBlankNodes() {
        assertEquals(listOf("Heading", "Plain text.", "A", "split", "paragraph.",
            "Entity & dash — ok", "Nested", "deep", "text"), ChapterHtml.textNodes(source).map { it.trim() })
    }

    @Test fun textNodeIndexesDoNotDependOnTextSizeOrPaper() {
        val served = ChapterHtml.prepare(source, 30, papers[2])
        assertEquals("A search index built once must still address the page at any text size",
            ChapterHtml.textNodes(source), ChapterHtml.textNodesOf(Jsoup.parse(served)))
    }

    @Test fun bookStylesAreKeptInThePageHeadAndOutOfTheReadingBody() {
        val styled = "<html><head><style>p { font-style: italic; }</style></head><body><p>Text</p></body></html>"
        assertFalse("A style tag must not survive inside the reading body", ChapterHtml.body(styled).contains("font-style"))
        assertTrue("Book styles must be hoisted into the page head", ChapterHtml.prepare(styled, 20, papers[0]).contains("font-style: italic"))
        assertEquals(listOf("Text"), ChapterHtml.textNodes(styled))
    }

    @Test fun anEmptyChapterHasNoTextNodes() {
        assertTrue(ChapterHtml.textNodes("<body><p>  </p><div></div></body>").isEmpty())
    }
}
