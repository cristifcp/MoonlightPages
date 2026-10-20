package com.moonlightpages.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

/**
 * FictionBook 2 — a whole book in one XML file: sections and their titles, footnotes, and every
 * image carried inside the file as base64.
 *
 * Rather than teach the reader a second format, an FB2 is turned into the shape an EPUB arrives in:
 * a folder holding a package, one chapter per titled section, a contents list that names them, and
 * the images written out as files. Everything the reader already does with a book — pagination,
 * chapter names, search, bookmarks, notes, the page-turn copy, backups — then works unchanged.
 */
internal object Fb2Book {
    /** The largest an FB2 file may be: it carries all of its own images inside it, as base64. */
    const val MAX_BYTES = 64 * 1024 * 1024

    private const val OEBPS = "OEBPS"

    /** The notes chapter, which footnote links point into. */
    private const val NOTES = "notes.xhtml"

    /** Whether these first bytes begin a FictionBook rather than something else. */
    fun looksLikeFb2(head: ByteArray): Boolean =
        String(head, Charsets.ISO_8859_1).contains("<FictionBook", ignoreCase = true)

    /**
     * Writes [bytes] into [folder] as a book the reader can open. The file's own XML declaration
     * decides how it is read, so books from the wild — as often windows-1251 as UTF-8 — come out
     * with their words intact.
     */
    fun convert(bytes: ByteArray, folder: File) {
        val parsed = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
        val root = parsed.children().firstOrNull { it.tagName().equals("FictionBook", ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "This file isn’t a book this app can read. EPUB, FictionBook (FB2) and MOBI files are supported.")
        val info = child(child(root, "description"), "title-info")
        val title = child(info, "book-title")?.text()?.trim().orEmpty().ifEmpty { "Untitled book" }
        val author = info?.children()?.filter { it.tagName() == "author" }
            ?.map { authorName(it) }?.filter { it.isNotEmpty() }
            ?.joinToString(", ")?.ifEmpty { null } ?: "Unknown author"
        val coverId = child(info, "coverpage")?.children()?.firstOrNull { it.tagName() == "image" }
            ?.let { reference(it) }

        val oebps = File(folder, "OEBPS").apply { mkdirs() }
        val images = writeImages(root, oebps)
        val cover = coverId?.let { images[it] }

        val bodies = root.children().filter { it.tagName() == "body" }
        val text = bodies.firstOrNull { it.attr("name") != "notes" } ?: bodies.firstOrNull()
            ?: throw IllegalArgumentException("This FictionBook has no text in it.")
        val writer = Writer(images)
        writer.write(text, title, writer.contents)
        bodies.firstOrNull { it.attr("name") == "notes" }?.let { notes ->
            // All the notes are one chapter, rather than one chapter each, and each keeps the id its
            // own text points at, so following a footnote lands on the note itself.
            writer.flat(notes, "Notes")
        }

        fun packaged(item: Entry): BookPackage.Entry =
            BookPackage.Entry(item.title, item.href, item.children.map { packaged(it) })
        val chapters = writer.chapters.map { BookPackage.Chapter(it.file, it.title, it.html.toString()) }
        chapters.forEach { BookPackage.writeChapter(folder, it) }
        BookPackage.write(folder, title, author, chapters, cover, writer.contents.map { packaged(it) })
    }

    /** One chapter of the book being written: the file it goes in, its name, and its markup. */
    private class Chapter(val file: String, val title: String, val html: StringBuilder = StringBuilder())

    /** A contents entry, holding whatever sections sit inside it. */
    private class Entry(val title: String, val href: String, val children: MutableList<Entry> = mutableListOf())

    /**
     * Writes a book's sections as chapters: one for every section, at any depth. A section is named
     * by its own title; a book that leaves its sections unnamed — which whole novels do — names each
     * one by the words it opens with, so nothing is left without a name and no book is left without
     * chapters. Words that follow a section belong to it, so a book whose parts outlast their
     * sections still reads in the order it was written.
     */
    private class Writer(private val images: Map<String, String>) {
        val chapters = mutableListOf<Chapter>()
        val contents = mutableListOf<Entry>()
        private var chapter: Chapter? = null

        /** Writes a body: its own words as a chapter, if it has any, and then each of its sections. */
        fun write(body: Element, fallbackName: String, entries: MutableList<Entry>, level: Int = 1) {
            if (hasWords(body)) {
                val title = child(body, "title")?.let { words(it) }?.takeIf { it.isNotEmpty() }
                val entry = start(title ?: fallbackName, title, level, entries)
                body.childNodes().forEach { node -> when {
                    node is Element && node.tagName() == "section" -> section(node, level + 1, entry.children)
                    node is Element && node.tagName() == "title" && title != null -> Unit
                    else -> markup(node, level)
                } }
            } else {
                body.childNodes().forEach { node ->
                    if (node is Element && node.tagName() == "section") section(node, level, entries)
                    else if (node is Element && node.tagName() == "title") Unit
                    else markup(node, level)
                }
            }
        }

        /** Writes a body as one chapter: its sections flow into it instead of becoming chapters. */
        fun flat(body: Element, heading: String) {
            start(heading, heading, level = 1, entries = contents, file = "$OEBPS/$NOTES")
            body.childNodes().forEach { node -> markup(node, level = 1) }
        }

        private fun section(element: Element, level: Int, entries: MutableList<Entry>) {
            val title = child(element, "title")?.let { words(it) }?.takeIf { it.isNotEmpty() }
            // A section the book leaves unnamed is named by the words it opens with.
            val entry = start(title ?: opening(element), title, level, entries)
            element.childNodes().forEach { node -> when {
                node is Element && node.tagName() == "section" -> section(node, level + 1, entry.children)
                // A titled section's own title has just been written as its chapter's heading.
                node is Element && node.tagName() == "title" && title != null -> Unit
                else -> markup(node, level)
            } }
        }

        /** Whether a body has words of its own, rather than only the sections it holds. */
        private fun hasWords(body: Element): Boolean = body.childNodes().any { node ->
            (node is TextNode && node.wholeText.isNotBlank()) ||
                (node is Element && node.tagName() !in setOf("section", "title") && node.text().isNotBlank())
        }

        /** The words a section opens with, for a book that gives its sections no name. */
        private fun opening(element: Element): String {
            val words = element.children().firstOrNull { it.tagName() == "p" }?.text() ?: element.text()
            return words.replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "Section" }
        }

        private fun start(
            name: String, heading: String?, level: Int, entries: MutableList<Entry>, file: String? = null,
        ): Entry {
            val chapter = Chapter(file ?: "$OEBPS/chapter${chapters.size + 1}.xhtml", name)
            chapters.add(chapter)
            this.chapter = chapter
            if (heading != null) {
                val size = level.coerceIn(1, 3)
                chapter.html.append("<h$size>${BookPackage.escape(heading)}</h$size>\n")
            }
            val entry = Entry(name, chapter.file.substringAfterLast('/'))
            entries.add(entry)
            return entry
        }

        private fun markup(node: org.jsoup.nodes.Node, level: Int) {
            val chapter = chapter ?: return
            when (node) {
                is TextNode -> chapter.html.append(BookPackage.escape(node.wholeText))
                is Element -> {
                    val tag = tagOf(node, level)
                    if (tag.open.isEmpty()) chapter.html.append(tag.close)   // a whole element of its own
                    else {
                        chapter.html.append(tag.open)
                        node.childNodes().forEach { inner -> markup(inner, level + 1) }
                        chapter.html.append(tag.close)
                    }
                }
                else -> Unit
            }
        }

        /** The markup a piece of FB2 becomes: its opening tag, and the tag that closes it. */
        private fun tagOf(node: Element, level: Int): Tag {
            val id = node.attr("id").takeIf { it.isNotEmpty() }?.let { " id=\"${BookPackage.escape(it)}\"" }.orEmpty()
            return when (node.tagName()) {
                "title" -> heading(level)
                "subtitle" -> heading(level + 1)
                "emphasis" -> Tag("<em$id>", "</em>")
                "strong" -> Tag("<strong$id>", "</strong>")
                "strikethrough" -> Tag("<s$id>", "</s>")
                "sub" -> Tag("<sub$id>", "</sub>")
                "sup" -> Tag("<sup$id>", "</sup>")
                "code" -> Tag("<code$id>", "</code>")
                "epigraph", "cite", "annotation", "poem" -> Tag("<blockquote$id>", "</blockquote>")
                "stanza" -> Tag("<div$id>", "</div>")
                "v", "text-author", "p" -> Tag("<p$id>", "</p>")
                // A blank line keeps the shape of a book that spaces its own paragraphs.
                "empty-line" -> Tag("", "<p>&#160;</p>")
                "table" -> Tag("<table$id>", "</table>")
                "tr" -> Tag("<tr$id>", "</tr>")
                "th" -> Tag("<th$id>", "</th>")
                "td" -> Tag("<td$id>", "</td>")
                "a" -> {
                    // A note points at a fragment in the notes chapter, which is a file of its own.
                    val target = reference(node).orEmpty()
                    val href = if (target.startsWith("http") || target.contains('.')) target else "$NOTES#$target"
                    Tag("<a$id href=\"${BookPackage.escape(href)}\">", "</a>")
                }
                "image" -> reference(node)?.let { images[it] }
                    ?.let { name -> Tag("", "<img src=\"${BookPackage.escape(name)}\"/>") } ?: Tag("", "")
                // Nothing to write of its own: whatever is inside it is what matters.
                "section" -> Tag("<div$id>", "</div>")
                else -> Tag("", "")
            }
        }

        private fun heading(level: Int): Tag {
            val size = level.coerceIn(1, 6)
            return Tag("<h$size>", "</h$size>")
        }
    }

    /** An opening tag and its closing tag, or a whole element when it stands alone. */
    private class Tag(val open: String, val close: String)

    /** Every image the book carries: written out, and mapped from the id its words point at. */
    private fun writeImages(root: Element, into: File): Map<String, String> {
        val written = mutableMapOf<String, String>()
        root.children().filter { it.tagName() == "binary" }.forEach { binary ->
            val id = binary.attr("id")
            if (id.isEmpty()) return@forEach
            val image = runCatching { Base64.getMimeDecoder().decode(binary.text()) }.getOrNull()
            if (image == null || image.isEmpty()) return@forEach
            var name = fileName(id, binary.attr("content-type"))
            var counted = 1
            while (written.containsValue(name)) name = fileName("$id-$counted", binary.attr("content-type")).also { counted++ }
            File(into, name).writeBytes(image)
            written[id] = name
        }
        return written
    }

    private fun fileName(id: String, contentType: String): String {
        val safe = id.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
        val extension = when (contentType.trim().lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "image/webp" -> "webp"
            else -> safe.substringAfterLast('.', "").lowercase()
        }
        val stem = safe.substringBeforeLast('.', safe).ifEmpty { "image" }
        return if (extension.isEmpty()) safe else "$stem.$extension"
    }

    private fun authorName(author: Element): String =
        listOf("first-name", "middle-name", "last-name", "nickname")
            .mapNotNull { name -> child(author, name)?.text()?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** FB2 points at its own images with `l:href`, in its own namespace, or with a plain `href`. */
    private fun reference(image: Element): String? =
        image.attr("l:href").ifEmpty { image.attr("href") }.removePrefix("#").takeIf { it.isNotEmpty() }

    private fun child(parent: Element?, name: String): Element? =
        parent?.children()?.firstOrNull { it.tagName() == name }

    private fun words(element: Element): String = element.text().replace(Regex("\\s+"), " ").trim()
}
