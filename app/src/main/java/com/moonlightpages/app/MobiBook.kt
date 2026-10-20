package com.moonlightpages.app

import java.io.File
import java.nio.charset.Charset

/**
 * MOBI — Amazon's older book format: a Palm database whose text is compressed, whose pictures are
 * carried as records rather than files, and whose markup is HTML with a few tags of its own.
 *
 * Like an FB2, it is turned into the shape an EPUB arrives in, so the reader itself knows nothing
 * about it. This reads the older MOBI — what Calibre writes, and what most DRM-free MOBIs in the
 * wild are — with plain or PalmDOC-compressed text. A book compressed with Amazon's Huffman
 * dictionary is refused with a plain explanation rather than half-read, and AZW3, which is another
 * structure inside the same container, is not attempted at all.
 *
 * Two number conventions live in one file: the Palm database and the PalmDOC header write their
 * numbers high byte first, while everything inside the MOBI header — and the EXTH records — write
 * them low byte first.
 */
internal object MobiBook {
    /** The largest a MOBI may be. */
    const val MAX_BYTES = 64 * 1024 * 1024

    private const val PALMDOC = 2

    /** Whether these first bytes begin a Palm database holding a MOBI book. */
    fun looksLikeMobi(head: ByteArray): Boolean =
        head.size >= 68 && String(head, 60, 8, Charsets.ISO_8859_1) == "BOOKMOBI"

    fun convert(bytes: ByteArray, folder: File) {
        val pdb = Pdb(bytes)
        require(pdb.count() > 1) { notMobi() }
        val header = pdb.record(0)
        require(header.size >= 20) { notMobi() }
        val compression = be16(header, 0)
        val textLength = be32(header, 4)
        val textRecords = be16(header, 8)
        // A damaged or unfamiliar file is told so plainly, before anything is blamed on DRM.
        require(compression in setOf(0, 1, PALMDOC, 17480)) { notMobi() }
        require(compression != 17480) {
            "This MOBI is compressed with Amazon’s Huffman dictionary, which this app can’t read yet. " +
                "Converting it to EPUB with Calibre will open it here."
        }
        require(be16(header, 12) == 0) {
            "This MOBI is DRM-protected, so it can’t be opened. Only DRM-free books can be read."
        }
        val at = mobiHeader(header) ?: throw IllegalArgumentException(notMobi())
        // Most writers put the MOBI header's numbers low byte first, but some put them high byte
        // first. Its own length tells them apart: it is a small number either way it is written.
        val lowFirst = le32(header, at + 4).toInt() in 24..0x400
        fun field(offset: Int): Int =
            if (lowFirst) le32(header, at + offset).toInt() else be32(header, at + offset)
        val headerLength = field(4).coerceAtLeast(24)
        val encoding = field(12)
        val charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        val firstImage = field(0x5C)
        val meta = if (field(0x70) and 0x40 != 0) {
            exth(header, at + headerLength, charset, lowFirst)
        } else {
            Meta()
        }
        val title = meta.title ?: name(header, field(0x44), field(0x48), charset)
            ?: pdb.name().takeIf { it.isNotBlank() } ?: "Untitled book"
        val author = meta.author ?: "Unknown author"

        // AZW3 keeps a newer book — KF8 — in the same container, with no text the older reader can
        // see. Left alone it would import as an empty book, so it is named for what it is instead.
        val version = if (headerLength >= 0x18) field(0x14) else 0
        require(version < 8) {
            "This is an AZW3 (KF8) file, which this app can’t read yet. Converting it to EPUB with " +
                "Calibre will open it here."
        }
        require(textRecords > 0 && textLength > 0) {
            "This MOBI holds no text this app can read. An AZW3 (KF8) file looks like this from the " +
                "outside; converting it to EPUB with Calibre will open it here."
        }
        // The text is compressed, so it may be several times the file. Far more than that is a
        // damaged header, and asking for it would take the whole phone's memory rather than a message.
        require(textLength <= MAX_BYTES && textLength.toLong() <= bytes.size.toLong() * 6 + 4096) {
            "This MOBI is damaged: its header claims more text than the file could hold."
        }

        require(compression == 0 || compression == 1 || compression == PALMDOC) {
            "This MOBI is compressed in a way this app can’t read. Converting it to EPUB will open it here."
        }
        val text = decompress(pdb, compression, textRecords, textLength)
        val decoded = String(text, 0, text.size.coerceAtMost(textLength), charset)
        require(decoded.isNotBlank()) {
            "This MOBI has no words in it, so there is nothing to read."
        }

        val oebps = File(folder, BookPackage.OEBPS).apply { mkdirs() }
        val images = writeImages(pdb, firstImage, oebps)
        val cover = meta.cover?.let { offset -> firstImage + offset }?.let { images[it] }

        // MOBI marks a new page — in practice a new chapter — with its own page-break tag.
        val parts = decoded.split(Regex("(?i)<mbp:pagebreak\\s*/?>")).map { it.trim() }.filter { it.isNotEmpty() }
        val latin = isLatin(parts)
        val chapters = (parts.ifEmpty { listOf(decoded) }).mapIndexed { index, part ->
            BookPackage.Chapter(
                file = "chapter${index + 1}.xhtml",
                title = chapterName(part, index, latin),
                html = markup(part, images, firstImage),
            )
        }
        chapters.forEach { BookPackage.writeChapter(folder, it) }
        BookPackage.write(folder, title, author, chapters, cover,
            chapters.map { BookPackage.Entry(it.title, it.file) })
    }

    /** How many records the file holds, and what each one is. */
    private class Pdb(private val bytes: ByteArray) {
        fun count(): Int = if (bytes.size >= 78) be16(bytes, 76) else 0
        fun name(): String = String(bytes, 0, 32, Charsets.ISO_8859_1).trim('\u0000', ' ')
        fun record(index: Int): ByteArray {
            require(index in 0 until count()) { notMobi() }
            val start = be32(bytes, 78 + index * 8)
            val end = if (index + 1 < count()) be32(bytes, 78 + (index + 1) * 8) else bytes.size
            require(start in 0..bytes.size && end in start..bytes.size) { notMobi() }
            return bytes.copyOfRange(start, end)
        }
    }

    /** What a MOBI calls itself: its title, its author, and which picture is its cover. */
    private class Meta(val author: String? = null, val title: String? = null, val cover: Int? = null)

    /** Where the MOBI header starts inside the first record, if this really is one. */
    private fun mobiHeader(record: ByteArray): Int? {
        val limit = minOf(record.size - 4, 16 + 0x200)
        return (16..limit).firstOrNull { at ->
            record[at] == 'M'.code.toByte() && record[at + 1] == 'O'.code.toByte() &&
                record[at + 2] == 'B'.code.toByte() && record[at + 3] == 'I'.code.toByte()
        }
    }

    private fun exth(record: ByteArray, at: Int, charset: Charset, lowFirst: Boolean): Meta {
        if (at + 12 > record.size || String(record, at, 4, Charsets.ISO_8859_1) != "EXTH") return Meta()
        fun number(offset: Int): Int =
            if (lowFirst) le32(record, offset).toInt() else be32(record, offset)
        val count = number(at + 8).coerceIn(0, 2000)
        var cursor = at + 12
        val authors = mutableListOf<String>()
        var title: String? = null
        var cover: Int? = null
        for (index in 0 until count) {
            if (cursor + 8 > record.size) break
            val type = number(cursor)
            val length = number(cursor + 4)
            if (length < 8) break
            val value = record.copyOfRange(cursor + 8, (cursor + length).coerceAtMost(record.size))
            when (type) {
                100 -> tidyName(text(value, charset))?.let { authors += it }
                // A title the publisher updated supersedes the one in the file's name.
                503 -> if (title == null) title = text(value, charset)
                201 -> if (value.size >= 4) {
                    cover = if (lowFirst) le32(value, 0).toInt() else be32(value, 0)
                }
            }
            cursor += length
        }
        return Meta(authors.distinct().joinToString(" & ").ifBlank { null }, title, cover)
    }

    /**
     * Some files name the shop that sold them as a second author, and some carry a name mangled by
     * that shop. Neither belongs on the shelf beside the person who wrote the book.
     */
    private fun tidyName(raw: String?): String? {
        if (raw == null) return null
        val words = raw.replace('[', ' ').replace(']', ' ')
            .split(' ', '\t', '\n').filter { it.isNotBlank() }
        val once = words.filterIndexed { index, word -> index == 0 || !word.equals(words[index - 1], true) }
        val name = once.joinToString(" ")
        if (name.isEmpty()) return null
        val shop = name.contains("://") || name.endsWith(".com", true) ||
            name.endsWith(".net", true) || name.endsWith(".org", true) ||
            name.startsWith("www.", true)
        return name.takeUnless { shop }
    }

    private fun text(value: ByteArray, charset: Charset): String? =
        String(value, charset).trim().trimEnd('\u0000').takeIf { it.isNotBlank() }

    private fun name(record: ByteArray, offset: Int, length: Int, charset: Charset): String? {
        if (offset <= 0 || length <= 0 || offset + length > record.size) return null
        return text(record.copyOfRange(offset, offset + length), charset)
    }

    /** The book's words, record by record, however they were compressed. */
    private fun decompress(pdb: Pdb, compression: Int, records: Int, length: Int): ByteArray {
        val out = ByteArray(length.coerceAtLeast(1))
        var written = 0
        for (index in 1..records.coerceAtMost(pdb.count() - 1)) {
            if (written >= length) break
            val record = pdb.record(index)
            val part = if (compression == PALMDOC) palmdoc(record, length - written) else record
            val take = minOf(part.size, length - written)
            System.arraycopy(part, 0, out, written, take)
            written += take
        }
        return out.copyOf(written)
    }

    /**
     * PalmDOC's own compression: short runs of raw bytes for anything that is not plain ASCII,
     * two-byte back-references into the words already written, and a code of its own for a space
     * followed by a letter, which is the commonest pair in prose.
     */
    private fun palmdoc(input: ByteArray, limit: Int): ByteArray {
        val out = ByteArray(minOf(limit, 64 * 1024) + 16)
        var written = 0
        var at = 0
        while (at < input.size && written < out.size) {
            val code = input[at++].toInt() and 0xFF
            when {
                code == 0 -> out[written++] = 0
                code <= 0x08 -> repeat(code) { if (at < input.size && written < out.size) out[written++] = input[at++] }
                code <= 0x7F -> out[written++] = code.toByte()
                code <= 0xBF -> {
                    if (at >= input.size) break
                    // A back-reference: the top bits mark it, the rest is the distance and the length.
                    val pair = ((code and 0x3F) shl 8) or (input[at++].toInt() and 0xFF)
                    val distance = pair shr 3
                    val run = (pair and 0x07) + 3
                    if (distance <= 0 || distance > written) break
                    repeat(run) {
                        if (written < out.size) out[written] = out[written - distance]
                        written++
                    }
                }
                else -> {
                    // A space, then a letter: the commonest pair in prose, given a code of its own.
                    if (written < out.size) out[written++] = ' '.code.toByte()
                    if (written < out.size) out[written++] = (code xor 0x80).toByte()
                }
            }
        }
        return out.copyOf(written.coerceAtMost(limit))
    }

    /** The book's pictures, keyed by the record their text points at. */
    private fun writeImages(pdb: Pdb, firstImage: Int, into: File): Map<Int, String> {
        val written = mutableMapOf<Int, String>()
        if (firstImage <= 0 || firstImage >= pdb.count()) return written
        for (index in firstImage until pdb.count()) {
            val record = runCatching { pdb.record(index) }.getOrNull() ?: continue
            val extension = imageKind(record) ?: continue
            val name = "image${index - firstImage + 1}.$extension"
            if (runCatching { File(into, name).writeBytes(record) }.isSuccess) written[index] = name
        }
        return written
    }

    private fun imageKind(record: ByteArray): String? = when {
        record.size < 4 -> null
        record[0] == 0xFF.toByte() && record[1] == 0xD8.toByte() -> "jpg"
        record[0] == 0x89.toByte() && record[1] == 'P'.code.toByte() &&
            record[2] == 'N'.code.toByte() && record[3] == 'G'.code.toByte() -> "png"
        record[0] == 'G'.code.toByte() && record[1] == 'I'.code.toByte() &&
            record[2] == 'F'.code.toByte() -> "gif"
        record[0] == 'B'.code.toByte() && record[1] == 'M'.code.toByte() -> "bmp"
        else -> null
    }

    /** MOBI's markup, turned into plain HTML: pictures by name, its own tags dropped. */
    private fun markup(part: String, images: Map<Int, String>, firstImage: Int): String {
        var html = part
        // Pictures are carried by record number rather than by name.
        html = Regex("(?i)<img[^>]*?recindex\\s*=\\s*\"?(\\d+)\"?[^>]*>").replace(html) { match ->
            val record = match.groupValues[1].toIntOrNull()?.let { firstImage + it - 1 }
            images[record ?: -1]?.let { "<img src=\"${BookPackage.escape(it)}\"/>" } ?: ""
        }
        // A link to a byte position can't be followed, so its words are kept and the link dropped.
        html = Regex("(?is)<a\\s[^>]*filepos\\s*=\\s*\"?\\d+\"?[^>]*>(.*?)</a>").replace(html, "$1")
        // Tags of MOBI's own, and tags that only carry styling, leave their words behind.
        html = Regex("(?i)</?(mbp:[a-z0-9]+|guide|key|indent|font)\\b[^>]*>").replace(html, "")
        // A MOBI is a whole document in itself. Every chapter here is a document of its own, so the
        // wrappers and the paved paths that only a MOBI reader understands are dropped.
        html = Regex("(?is)<head\\b[^>]*>.*?</head\\s*>").replace(html, "")
        html = Regex("(?is)<title\\b[^>]*>.*?</title\\s*>").replace(html, "")
        html = Regex("(?is)<reference\\b[^>]*/?>").replace(html, "")
        html = Regex("(?is)</?(html|head|body)\\b[^>]*>").replace(html, "")
        return html
    }

    /**
     * A chapter's name: its first heading, or the short line it opens with — the way a story is
     * titled in books that have no headings at all — or else the words it opens with. A line that is
     * only a shop's advertisement, or the wreckage of a broken tag, is passed over.
     */
    private fun chapterName(part: String, index: Int, latin: Boolean): String {
        val heading = Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(part)?.groupValues?.get(1)
        val lines = Regex("(?is)<p\\b[^>]*>(.*?)</p>").findAll(part).take(8)
            .map { plain(it.groupValues[1]) }.toList()
        (sequenceOf(heading?.let(::plain)) + lines.asSequence())
            .filterNotNull().firstOrNull { fits(it, latin) }
            ?.let { return it }
        // Nothing at the top will do: name it by the first stretch of real prose instead.
        val opening = lines.firstOrNull { it.length >= 80 && prose(it, latin) }
        return opening?.take(60) ?: "Section ${index + 1}"
    }

    /** Whether a line could stand as a chapter's name in a book written this way. */
    private fun fits(line: String, latin: Boolean): Boolean =
        line.length in 2..70 && prose(line, latin)

    /** Whether a line is this book's own writing, rather than a shop's note or a broken tag. */
    private fun prose(line: String, latin: Boolean): Boolean {
        if (line.contains("=\"") || line.contains("://")) return false
        return !latin || line.any { it in 'A'..'Z' || it in 'a'..'z' || it in '\u00C0'..'\u024F' }
    }

    /** Whether the book is written in Latin letters, which decides what can name a chapter. */
    private fun isLatin(parts: List<String>): Boolean {
        var letters = 0
        var latin = 0
        for (part in parts) {
            for (character in part) {
                if (!character.isLetter()) continue
                if (character in 'A'..'Z' || character in 'a'..'z' || character in '\u00C0'..'\u024F') latin++
                if (++letters > 40_000) return latin * 2 >= letters
            }
        }
        return letters == 0 || latin * 2 >= letters
    }

    /** Text with its markup and its HTML codes taken out, as it would read on the page. */
    private fun plain(html: String): String {
        val unmasked = Regex("&#(\\d{1,7});").replace(html.replace(Regex("<[^>]+>"), " ")) { match ->
            runCatching { String(Character.toChars(match.groupValues[1].toInt())) }.getOrDefault("")
        }
        return unmasked
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun notMobi() = "This file isn’t a MOBI this app can read."

    /** The Palm database and the PalmDOC header write their numbers high byte first. */
    private fun be16(bytes: ByteArray, at: Int): Int {
        require(at + 2 <= bytes.size) { notMobi() }
        return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    }

    private fun be32(bytes: ByteArray, at: Int): Int {
        require(at + 4 <= bytes.size) { notMobi() }
        return ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
    }

    /** Inside the MOBI header, and in its EXTH records, they are the other way round. */
    private fun le32(bytes: ByteArray, at: Int): Long {
        require(at + 4 <= bytes.size) { notMobi() }
        return ((bytes[at + 3].toLong() and 0xFF) shl 24) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            (bytes[at].toLong() and 0xFF)
    }
}
