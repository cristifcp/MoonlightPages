package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/** Turning a FictionBook into the shape the reader opens: chapters, names, images, footnotes. */
class Fb2BookTest {
    private val folder = File(System.getProperty("java.io.tmpdir"), "fb2-test-${System.nanoTime()}")

    private fun book(): String = """<?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description><title-info>
            <author><first-name>Herman</first-name><last-name>Melville</last-name></author>
            <book-title>Loomings &amp; All</book-title>
            <coverpage><image l:href="#cover.png"/></coverpage>
          </title-info></description>
          <body>
            <title><p>Loomings</p></title>
            <section>
              <title><p>Chapter One</p></title>
              <p>Call me Ishmael.</p>
              <empty-line/>
              <p>Some years ago<emphasis>never mind how long</emphasis> precisely.</p>
              <section><title><p>A Subsection</p></title><p>Water, and the whale.</p></section>
            </section>
            <section>
              <title><p>Chapter Two</p></title>
              <p>The carpet bag.<a l:href="#n1" type="note">[1]</a></p>
            </section>
          </body>
          <body name="notes">
            <section id="n1"><title><p>Note One</p></title><p>A note about the bag.</p></section>
          </body>
          <binary id="cover.png" content-type="image/png">$PNG</binary>
        </FictionBook>"""

    private fun convert(xml: String, charset: Charset = Charsets.UTF_8): File {
        folder.deleteRecursively()
        folder.mkdirs()
        Fb2Book.convert(xml.toByteArray(charset), folder)
        return folder
    }

    @Test fun aFictionBookIsRecognisedByWhatItOpensWith() {
        assertTrue(Fb2Book.looksLikeFb2(book().toByteArray()))
        assertFalse(Fb2Book.looksLikeFb2(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertFalse(Fb2Book.looksLikeFb2("<html><body>Not a book</body></html>".toByteArray()))
    }

    @Test fun theBookIsWrittenAsAChapterPerTitledSection() {
        val root = convert(book())
        val first = File(root, "OEBPS/chapter1.xhtml").readText()
        val subsection = File(root, "OEBPS/chapter2.xhtml").readText()
        val second = File(root, "OEBPS/chapter3.xhtml").readText()
        val notes = File(root, "OEBPS/notes.xhtml").readText()

        assertTrue("the first chapter is named", first.contains("<h1>Chapter One</h1>"))
        assertTrue("and holds its words", first.contains("Call me Ishmael."))
        assertTrue("with emphasis kept", first.contains("<em>never mind how long</em>"))
        assertTrue("a blank line kept as one", first.contains("&#160;"))
        assertTrue("a titled section inside one is a chapter of its own, one level in",
            subsection.contains("<h2>A Subsection</h2>") && subsection.contains("Water, and the whale."))
        assertTrue("the chapter after it comes next", second.contains("<h1>Chapter Two</h1>"))

        assertTrue("a note is a link into the notes chapter",
            second.contains("""href="notes.xhtml#n1""""))
        assertTrue("and the notes name the passage they belong to", notes.contains("A note about the bag."))
    }

    @Test fun thePackageNamesTheBookAndItsChapters() {
        val root = convert(book())
        val opf = File(root, "OEBPS/content.opf").readText()
        assertTrue("the title survives its escaping", opf.contains("<dc:title>Loomings &amp; All</dc:title>"))
        assertTrue(opf.contains("<dc:creator>Herman Melville</dc:creator>"))
        assertTrue(opf.contains("""href="chapter1.xhtml""""))
        assertTrue(opf.contains("""properties="nav""""))
        assertTrue("the cover is declared", opf.contains("""properties="cover-image""""))
        assertTrue("and pointed at by the container",
            File(root, "META-INF/container.xml").readText().contains("OEBPS/content.opf"))
    }

    @Test fun theContentsNamesEveryChapterTheBookHas() {
        val nav = File(convert(book()), "OEBPS/nav.xhtml").readText()
        assertTrue("the book's own chapters are named",
            nav.contains(">Chapter One<") && nav.contains(">Chapter Two<") && nav.contains(">Notes<"))
        assertTrue("with the part inside the first one nested under it",
            nav.contains("<ol><li><a href=\"chapter1.xhtml\">Chapter One</a><ol>") &&
                nav.contains(">A Subsection<"))
    }

    @Test fun theBooksImagesAreWrittenOutAndPointedAt() {
        val root = convert(book())
        val cover = File(root, "OEBPS/cover.png")
        assertTrue("the image is written as a file", cover.isFile)
        assertTrue("and it is the picture the book carried",
            cover.readBytes().take(4).toByteArray().contentEquals(
                byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
    }

    @Test fun aBookWrittenInAnotherAlphabetIsReadAsItWasWritten() {
        // Plenty of FictionBooks in the wild are windows-1251, and say so in their own declaration.
        val russian = book().replace("utf-8\"?>", "windows-1251\"?>").replace("Call me Ishmael.", "Зовите меня Измаил.")
        val root = convert(russian, Charset.forName("windows-1251"))
        assertTrue("the words come through as written",
            File(root, "OEBPS/chapter1.xhtml").readText().contains("Зовите меня Измаил."))
    }

    @Test fun aBookThatNamesNoneOfItsSectionsStillGetsChapters() {
        // Whole novels arrive this way: one body, sections with no titles at all. Each is still a
        // chapter, named by the words it opens with, and the contents can name them too.
        val unnamed = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>Untitled parts</book-title></title-info></description>
              <body>
                <section><p>The town lay under a grey sky.</p></section>
                <section><p>Later, the rain came.</p></section>
                <section><p>By morning nothing was left.</p></section>
              </body>
            </FictionBook>"""
        val root = convert(unnamed)
        val chapters = File(root, "OEBPS").listFiles().orEmpty()
            .filter { it.name.startsWith("chapter") }.sortedBy { it.name }
        assertEquals("every section is a chapter, even when the book names none", 3, chapters.size)
        assertTrue("and it is named by the words it opens with",
            chapters.first().readText().contains("The town lay under a grey sky."))
        val nav = File(root, "OEBPS/nav.xhtml").readText()
        assertTrue("the contents can name them as well", nav.contains("The town lay under a grey sky."))
    }

    companion object {
        /** A one-pixel PNG, the way a book carries its pictures: base64 in the file itself. */
        private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
