package com.moonlightpages.app

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ChapterHtmlTest {
    @Test fun removesActiveContentAndPreservesBookText() {
        val html = ChapterHtml.prepare("""
            <html><head><base href="https://bad.example/"/><meta http-equiv="refresh" content="0; url=https://bad.example"/>
            <script>window.untrusted = true;</script><style>p { font-style: italic; }</style></head>
            <body onload="attack()"><iframe srcdoc="unsafe"></iframe><object data="unsafe"></object>
            <p id="passage" onclick="attack()">A real passage.</p><a href="javascript:attack()">Bad link</a>
            <img src="image.jpg" onerror="attack()"/><input autofocus="autofocus"/></body></html>
        """.trimIndent(), 20, papers[1])
        val document = Jsoup.parse(html)
        assertTrue(document.select("script, iframe, object, input, base, [onclick], [onload], [onerror], [srcdoc]").isEmpty())
        assertEquals("A real passage.", document.getElementById("passage")!!.text())
        assertFalse(document.selectFirst("a")!!.hasAttr("href"))
        assertEquals("image.jpg", document.selectFirst("img")!!.attr("src"))
        assertFalse(document.selectFirst("meta")!!.hasAttr("http-equiv"))
        assertTrue(html.contains("font-style: italic"))
        assertEquals(1, document.select("#__moonlightPages").size)
    }

    @Test fun bookCannotReplacePaginationContainers() {
        val html = ChapterHtml.prepare("<div id='__moonlightPages'>Text</div>", 24, papers[0])
        assertEquals(1, Jsoup.parse(html).select("#__moonlightPages").size)
    }

    @Test fun imagesAreLoadedBeforePaginationInsteadOfOnTheFirstPageTurn() {
        val html = ChapterHtml.prepare("<p>Before</p><img loading='lazy' src='illustration.jpg'/><p>After</p>", 20, papers[1])
        val document = Jsoup.parse(html)
        assertEquals("eager", document.selectFirst("img")!!.attr("loading"))
        assertEquals("illustration.jpg", document.selectFirst("img")!!.attr("src"))
    }

    @Test fun everyPieceOfTextCarriesTheHeadingItSitsUnder() {
        val (nodes, headings) = ChapterHtml.textWithHeadings("""
            <html><body><h1>A Quiet Beginning</h1><h2>Part One</h2><p>first words</p>
            <h2>Part Two</h2><p>second words</p></body></html>
        """.trimIndent())
        assertEquals(listOf("A Quiet Beginning", "Part One", "first words", "Part Two", "second words"), nodes)
        assertEquals(listOf("A Quiet Beginning", "Part One", "Part One", "Part Two", "Part Two"), headings)
    }

    @Test fun textWithNoHeadingIsLeftForTheBookToName() {
        val (nodes, headings) = ChapterHtml.textWithHeadings("<html><body><p>Just words.</p></body></html>")
        assertEquals(listOf("Just words."), nodes)
        assertEquals(listOf(""), headings)
    }

    @Test fun anAnchorIsReachedAtTheFirstTextThatComesAfterIt() {
        val text = ChapterHtml.chapterText("""
            <html><body><p>Before</p><h2 id="two">Two</h2><p>after</p><div id="three"></div><p>last</p>
            </body></html>
        """.trimIndent(), setOf("two", "three", "missing"))
        assertEquals(listOf("Before", "Two", "after", "last"), text.nodes)
        assertEquals(mapOf("two" to 1, "three" to 3), text.anchors)
    }

    @Test fun aChapterWithoutBodyTextNamesNothingAndCarriesNothing() {
        val (nodes, headings) = ChapterHtml.textWithHeadings("<html><body><h2>Only a heading</h2></body></html>")
        assertEquals(listOf("Only a heading"), nodes)
        assertEquals(listOf("Only a heading"), headings)
    }

    /** The page keeps the room it is given, sits its lines as far apart as asked, and in the face named. */
    @Test fun thePageIsSetTheWayTheReaderChoseIt() {
        val chapter = "<html><body><p>Words</p></body></html>"
        val normal = ChapterHtml.prepare(chapter, 20, papers[1])
        assertTrue("A page keeps its room at the sides", normal.contains("left:24px"))
        assertTrue("and a little less above and below", normal.contains("top:20px"))
        assertTrue("the next page is exactly one viewport away",
            normal.contains("column-gap:48px") && normal.contains("width:calc(100vw - 48px)"))
        assertTrue("lines are set 1.65 apart by default", normal.contains("line-height:1.65"))

        val wide = ChapterHtml.prepare(chapter, 20, papers[1], inset = 2)
        assertTrue(wide.contains("left:40px") && wide.contains("column-gap:80px"))
        val narrow = ChapterHtml.prepare(chapter, 20, papers[1], inset = 0)
        assertTrue(narrow.contains("left:12px") && narrow.contains("column-gap:24px"))

        assertTrue(ChapterHtml.prepare(chapter, 20, papers[1], spacing = 0).contains("line-height:1.45"))
        assertTrue(ChapterHtml.prepare(chapter, 20, papers[1], spacing = 2).contains("line-height:1.95"))

        assertTrue(ChapterHtml.prepare(chapter, 20, papers[1], font = 0).contains("Georgia"))
        assertTrue(ChapterHtml.prepare(chapter, 20, papers[1], font = 1).contains("sans-serif"))
    }
}
