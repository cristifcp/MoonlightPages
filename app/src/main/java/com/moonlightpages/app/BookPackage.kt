package com.moonlightpages.app

import java.io.File

/**
 * The shape every converted book is written in: the folder an EPUB arrives as — a package naming
 * the book, one chapter file per part, a contents list naming them, and the images the text points
 * at. FictionBook and MOBI are both turned into this, so the reader needs to know nothing about
 * either of them.
 */
internal object BookPackage {
    const val OEBPS = "OEBPS"
    const val PACKAGE = "$OEBPS/content.opf"
    const val NAV = "$OEBPS/nav.xhtml"

    /** One chapter: the file it is written to (inside OEBPS), its name, and its markup. */
    data class Chapter(val file: String, val title: String, val html: String)

    /** A contents entry, holding whatever entries sit inside it. */
    data class Entry(val title: String, val href: String, val children: List<Entry> = emptyList())

    /** Writes the package and the contents. Chapters and images are written by the converter itself. */
    fun write(
        folder: File, title: String, author: String,
        chapters: List<Chapter>, cover: String?, contents: List<Entry>,
    ) {
        File(folder, NAV).writeText(contentsPage(contents))
        File(folder, PACKAGE).writeText(packagePage(title, author, chapters, cover))
        File(folder, "META-INF").apply { mkdirs() }
        File(folder, "META-INF/container.xml").writeText(containerPage())
    }

    /** Writes one chapter's file, wrapped as a page of its own. */
    fun writeChapter(folder: File, chapter: Chapter) {
        File(folder, "$OEBPS/${chapter.file.substringAfterLast('/')}")
            .writeText(page(chapter.title, chapter.html))
    }

    private fun page(title: String, html: String): String = """<?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>${escape(title)}</title></head>
        <body>$html</body></html>
        """.trimIndent()

    private fun contentsPage(entries: List<Entry>): String = """<?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <head><title>Contents</title></head><body>
        <nav epub:type="toc"><ol>${entries.joinToString("") { entry(it) }}</ol></nav>
        </body></html>
        """.trimIndent()

    private fun entry(item: Entry): String {
        val inside = if (item.children.isEmpty()) "" else "<ol>${item.children.joinToString("") { entry(it) }}</ol>"
        return "<li><a href=\"${escape(item.href)}\">${escape(item.title)}</a>$inside</li>"
    }

    private fun packagePage(title: String, author: String, chapters: List<Chapter>, cover: String?): String {
        val manifest = chapters.mapIndexed { index, chapter ->
            "<item id=\"c$index\" href=\"${escape(chapter.file.substringAfterLast('/'))}\" " +
                "media-type=\"application/xhtml+xml\"/>"
        }.joinToString("\n") +
            "\n<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
            (cover?.let { "\n<item id=\"cover-image\" href=\"${escape(it)}\" properties=\"cover-image\"/>" } ?: "")
        val spine = chapters.indices.joinToString("\n") { "<itemref idref=\"c$it\"/>" }
        return """<?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:identifier id="book">${escape(title)}</dc:identifier>
        <dc:title>${escape(title)}</dc:title><dc:creator>${escape(author)}</dc:creator>
        <dc:language>en</dc:language></metadata>
        <manifest>$manifest</manifest>
        <spine>$spine</spine></package>
        """.trimIndent()
    }

    private fun containerPage(): String = """<?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        <rootfiles><rootfile full-path="$PACKAGE" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
        """.trimIndent()

    /** Escapes the few characters that would otherwise be read as markup, and leaves the rest alone. */
    fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
