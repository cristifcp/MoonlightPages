package com.moonlightpages.app

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A tiny two-chapter book built in memory, used by the device tests so they stay quick and their
 * expectations stay small. The book the app itself ships with is read from its assets instead.
 */
internal fun sampleBook(store: EpubStore): Book = store.importBook(sampleEpubBytes().inputStream())

/** The same tiny book as real EPUB bytes, for tests that hand it over the way a file arrives. */
internal fun sampleEpubBytes(): ByteArray = epubBytes { entry ->
    entry("book.opf", packageXml("A Quiet Beginning", listOf("one.xhtml", "two.xhtml"), nav = null))
    entry("one.xhtml", chapterXml("A Quiet Beginning", body(paragraphs)))
    entry("two.xhtml", chapterXml("One More Page", body(paragraphs.reversed())))
}

/**
 * A book packaged the way a printed novel usually is: its index names three chapters inside the
 * first file and one inside the second. Used to check that a reading position is reported in the
 * chapters the book itself names rather than in the files it happens to be stored in.
 */
internal fun groupedChaptersEpubBytes(): ByteArray = epubBytes { entry ->
    val nav = listOf("one.xhtml#first" to "Chapter One", "one.xhtml#second" to "Chapter Two",
        "one.xhtml#third" to "Chapter Three", "two.xhtml#fourth" to "Chapter Four")
    entry("book.opf", packageXml("Three Chapters In One File", listOf("one.xhtml", "two.xhtml"), nav))
    entry("nav.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>${
        nav.joinToString("") { (target, title) -> """<li><a href="$target">$title</a></li>""" }
    }</ol></nav></body></html>""")
    entry("one.xhtml", chapterXml("Three in a row", paragraphs.take(3).mapIndexed { index, text ->
        "<h2 id=\"${listOf("first", "second", "third")[index]}\">Chapter ${index + 1}</h2><p>$text</p>"
    }.joinToString("")))
    entry("two.xhtml", chapterXml("The last one", "<h2 id=\"fourth\">Chapter Four</h2>${body(paragraphs.take(1))}"))
}

private fun body(paragraphs: List<String>): String = paragraphs.joinToString("") { "<p>$it</p>" }

/**
 * A book packaged the way most real ones are: its chapter and its cover sit in folders of their own.
 * Used to check that a backup keeps a book's own layout, and not only the words inside it.
 */
internal fun nestedEpubBytes(): ByteArray = epubBytes { entry ->
    entry("book.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">moonlight-nested</dc:identifier><dc:title>A Book In Folders</dc:title><dc:creator>Moonlight Pages</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="cover" href="OEBPS/cover.png" media-type="image/png" properties="cover-image"/><item id="file0" href="OEBPS/text/one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="file0"/></spine></package>""")
    entry("OEBPS/cover.png", "a file of the book's own, kept beside it")
    entry("OEBPS/text/one.xhtml", chapterXml("A Book In Folders", body(paragraphs.take(2))))
}

private val paragraphs = listOf(
    "There is a particular kind of quiet that arrives when you open a book. The room stays exactly as it was, yet something changes. A little space appears between you and the hurry of the day.",
    "Reading does not ask you to be somewhere else on time. It asks only that you be here, with this sentence, and then the next. Some days a single page is enough.",
    "By the window, the light moves slowly across the table. Outside, someone is walking home. Inside, a story is just beginning. There is no need to rush it.",
    "This is your little library. Bring the books you love, the books you have been meaning to read, and the ones you found by chance. Leave room for a surprise.",
    "Try changing the paper colour or making the letters a little larger. A good reading space should fit you, not the other way around.",
    "When you leave, your place will stay here. Close the book, make some tea, and come back when you are ready. The next chapter can wait."
)

private fun epubBytes(write: (entry: (String, String) -> Unit) -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        val entry = { name: String, text: String ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("mimetype", "application/epub+zip")
        entry("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        write(entry)
    }
    return bytes.toByteArray()
}

private fun packageXml(title: String, files: List<String>, nav: List<Pair<String, String>>?): String {
    val manifest = files.mapIndexed { index, file ->
        """<item id="file$index" href="$file" media-type="application/xhtml+xml"/>"""
    }.joinToString("")
    val navItem = if (nav == null) "" else
        """<item id="contents" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""
    val spine = files.indices.joinToString("") { """<itemref idref="file$it"/>""" }
    return """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">moonlight-sample</dc:identifier><dc:title>$title</dc:title><dc:creator>Moonlight Pages</dc:creator><dc:language>en</dc:language></metadata><manifest>$manifest$navItem</manifest><spine>$spine</spine></package>"""
}

internal fun chapterXml(title: String, body: String): String =
    """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>$body</body></html>"""
