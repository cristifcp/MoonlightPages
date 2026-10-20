package com.moonlightpages.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reading a MOBI: a Palm database, PalmDOC compression, the MOBI header and its EXTH records, and
 * the book's own markup. Each of those is built by hand here, so a failure says which one broke.
 */
class MobiBookTest {
    private val folder = File(System.getProperty("java.io.tmpdir"), "mobi-test-${System.nanoTime()}")

    /** A whole MOBI, from the words in it to the pictures it carries. */
    private fun mobi(
        text: String, title: String = "A Palm Book", authors: List<String> = listOf("Herman Melville"),
        images: List<ByteArray> = emptyList(), cover: Int? = null, compression: Int = 2,
        bigEndian: Boolean = false, version: Int = 6, declaredLength: Int = -1,
        declaredRecords: Int = -1, compress: (ByteArray) -> ByteArray = ::literal,
    ): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val chunks = bytes.toList().chunked(4000).map { it.toByteArray() }
        val firstImage = 1 + chunks.size
        val records = mutableListOf<ByteArray>()
        records.add(header(bytes.size, chunks.size, firstImage, title, authors, cover, compression,
            bigEndian, version, declaredLength, declaredRecords))
        chunks.forEach { chunk -> records.add(if (compression == 2) compress(chunk) else chunk) }
        records.addAll(images)
        return pdb(records)
    }

    /** A book's first record: the PalmDOC header, the MOBI header, its EXTH records, its full name. */
    private fun header(
        length: Int, chapters: Int, firstImage: Int, title: String, authors: List<String>,
        cover: Int?, compression: Int, bigEndian: Boolean, version: Int, declaredLength: Int,
        declaredRecords: Int,
    ): ByteArray {
        val name = title.toByteArray(Charsets.UTF_8)
        val exth = exth(authors, cover, bigEndian)
        val field: (ByteArray, Int, Int) -> Unit = if (bigEndian) ::write32 else ::writeLe32
        val headerLength = 0xE4
        val nameAt = 16 + headerLength + exth.size
        val record = ByteArray(nameAt + name.size)
        // PalmDOC, high byte first.
        write16(record, 0, compression)
        write32(record, 4, if (declaredLength >= 0) declaredLength else length)
        write16(record, 8, if (declaredRecords >= 0) declaredRecords else chapters)
        write16(record, 10, 4096)
        write16(record, 12, 0)
        // MOBI, low byte first in most files and high byte first in a few.
        "MOBI".forEachIndexed { index, c -> record[16 + index] = c.code.toByte() }
        field(record, 16 + 4, headerLength)
        field(record, 16 + 8, 2)
        field(record, 16 + 12, 65001)
        field(record, 16 + 0x14, version)
        field(record, 16 + 0x44, nameAt)
        field(record, 16 + 0x48, name.size)
        field(record, 16 + 0x5C, firstImage)
        field(record, 16 + 0x70, if (exth.isEmpty()) 0 else 0x40)
        exth.copyInto(record, 16 + headerLength)
        name.copyInto(record, nameAt)
        return record
    }

    private fun exth(authors: List<String>, cover: Int?, bigEndian: Boolean): ByteArray {
        val field: (ByteArray, Int, Int) -> Unit = if (bigEndian) ::write32 else ::writeLe32
        val items = mutableListOf<Pair<Int, ByteArray>>()
        authors.forEach { items.add(100 to it.toByteArray(Charsets.UTF_8)) }
        cover?.let { items.add(201 to byteArrayOf(it.toByte(), 0, 0, 0)) }
        val body = items.map { (type, value) ->
            val out = ByteArray(8 + value.size)
            field(out, 0, type)
            field(out, 4, out.size)
            value.copyInto(out, 8)
            out
        }
        val size = 12 + body.sumOf { it.size }
        val out = ByteArray(size)
        "EXTH".forEachIndexed { index, c -> out[index] = c.code.toByte() }
        field(out, 4, size)
        field(out, 8, items.size)
        var at = 12
        body.forEach { it.copyInto(out, at); at += it.size }
        return out
    }

    /** A Palm database: a header, a record table, a two-byte gap, then the records. */
    private fun pdb(records: List<ByteArray>): ByteArray {
        val header = ByteArray(78 + records.size * 8)
        "Test Book".toByteArray().copyInto(header, 0)
        write16(header, 76, records.size)
        var at = header.size + 2
        records.forEachIndexed { index, record ->
            write32(header, 78 + index * 8, at)
            at += record.size
        }
        val out = ByteArray(at)
        header.copyInto(out)
        var writing = header.size + 2
        records.forEach { record -> record.copyInto(out, writing); writing += record.size }
        // What makes a Palm database one of Amazon's books.
        "BOOKMOBI".forEachIndexed { index, c -> out[60 + index] = c.code.toByte() }
        return out
    }

    /** PalmDOC compression done the simplest way that still exercises the reader: literals. */
    private fun literal(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (value in bytes) {
            val code = value.toInt() and 0xFF
            when {
                code == 0 -> out.write(0)
                code <= 0x08 -> { out.write(1); out.write(code) }
                code <= 0x7F -> out.write(code)
                else -> { out.write(1); out.write(code) }
            }
        }
        return out.toByteArray()
    }

    private fun write16(target: ByteArray, at: Int, value: Int) {
        target[at] = (value shr 8).toByte()
        target[at + 1] = value.toByte()
    }

    private fun write32(target: ByteArray, at: Int, value: Int) {
        target[at] = (value shr 24).toByte()
        target[at + 1] = (value shr 16).toByte()
        target[at + 2] = (value shr 8).toByte()
        target[at + 3] = value.toByte()
    }

    private fun writeLe32(target: ByteArray, at: Int, value: Int) {
        target[at] = value.toByte()
        target[at + 1] = (value shr 8).toByte()
        target[at + 2] = (value shr 16).toByte()
        target[at + 3] = (value shr 24).toByte()
    }

    private fun convert(bytes: ByteArray): File {
        folder.deleteRecursively()
        folder.mkdirs()
        MobiBook.convert(bytes, folder)
        return folder
    }

    @Test fun aMobiIsRecognisedByItsPalmDatabaseType() {
        assertTrue(MobiBook.looksLikeMobi(mobi("<p>Hello</p>")))
        assertFalse(MobiBook.looksLikeMobi(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertFalse(MobiBook.looksLikeMobi("<FictionBook/>".toByteArray()))
    }

    @Test fun aCompressedBookBecomesChaptersWithItsPicturesAndCover() {
        val root = convert(mobi(
            text = "<h1>Chapter One</h1><p>Call me Ishmael.</p><mbp:pagebreak/>" +
                "<h1>Chapter Two</h1><p>The carpet bag.<img recindex=\"00001\"/></p>",
            images = listOf(PNG),
            cover = 0,
        ))

        val first = File(root, "OEBPS/chapter1.xhtml").readText()
        val second = File(root, "OEBPS/chapter2.xhtml").readText()
        assertTrue("the first chapter keeps its words", first.contains("Call me Ishmael."))
        assertTrue("MOBI's own tags are dropped", !second.contains("mbp:"))
        assertTrue("and its picture is written out and pointed at",
            File(root, "OEBPS/image1.png").isFile && second.contains("""src="image1.png""""))

        val opf = File(root, "OEBPS/content.opf").readText()
        assertTrue("the book's own name and author", opf.contains("A Palm Book") && opf.contains("Herman Melville"))
        assertTrue("and its cover declared", opf.contains("""properties="cover-image""""))

        val nav = File(root, "OEBPS/nav.xhtml").readText()
        assertTrue("the contents names the chapters the book has",
            nav.contains(">Chapter One<") && nav.contains(">Chapter Two<"))
    }

    @Test fun aBackReferenceIsExpandedIntoTheWordsItPointsAt() {
        // "abc", then a back-reference three bytes back, six long: "abcabcabc".
        val record = byteArrayOf(0x61, 0x62, 0x63, 0x80.toByte(), 0x1B)
        val root = convert(mobi("<p>abcabcabc</p>", compress = { record }))
        assertTrue("the repeated words are restored",
            File(root, "OEBPS/chapter1.xhtml").readText().contains("abcabcabc"))
    }

    @Test fun aSpaceAndALetterShareOneCode() {
        // Prose is mostly "a space, then a letter", and PalmDOC gives that pair a code of its own.
        val record = byteArrayOf(0x61, 0xF4.toByte(), 0x62)
        val root = convert(mobi("<p>a tb</p>", compress = { record }))
        assertTrue("the space and letter come back",
            File(root, "OEBPS/chapter1.xhtml").readText().contains("a tb"))
    }

    @Test fun aHeaderWrittenHighByteFirstReadsTheSameAsOneWrittenLowByteFirst() {
        // Most writers put the MOBI header's numbers low byte first; some put them the other way.
        val root = convert(mobi("<p>Call me Ishmael.</p>", title = "Moby Dick", bigEndian = true))
        val opf = File(root, "OEBPS/content.opf").readText()
        assertTrue("the title is read in either order: $opf", opf.contains("Moby Dick"))
        assertTrue("and so is the author", opf.contains("Herman Melville"))
        assertTrue("and the words come through",
            File(root, "OEBPS/chapter1.xhtml").readText().contains("Call me Ishmael."))
    }

    @Test fun theShopThatSoldTheBookIsNotAnAuthor() {
        val root = convert(mobi(
            "<p>Words</p>",
            authors = listOf("Andrzej] Andrzej Sapkowski [Sapkowski", "chenjin5.com"),
        ))
        val opf = File(root, "OEBPS/content.opf").readText()
        assertTrue("the name is written once: $opf", opf.contains("Andrzej Sapkowski"))
        assertTrue("and the shop is left out", !opf.contains("chenjin5") && !opf.contains("Andrzej] "))
    }

    @Test fun aChapterWithNoHeadingTakesItsNameFromAShortFirstLine() {
        val root = convert(mobi(
            "<p height=\"1em\" width=\"0pt\"> The Limits of the Possible</p>" +
                "<p height=\"1em\"> &quot;He's not coming back out,&quot; he said.</p>" +
                "<mbp:pagebreak/><p>A long paragraph that carries on well past the point where a name" +
                " would stop being useful, so it must be cut short.</p>",
        ))
        val nav = File(root, "OEBPS/nav.xhtml").readText()
        assertTrue("the short first line names the chapter: $nav",
            nav.contains(">The Limits of the Possible<"))
        assertTrue("and a long opening is cut short rather than quoted whole",
            nav.contains(">A long paragraph that carries on well past the point") &&
                !nav.contains("must be cut short."))
    }

    @Test fun aChapterKeepsItsWordsButNotTheDocumentsWrappers() {
        val root = convert(mobi(
            "<html><head><title>A Title</title><guide><reference type=\"toc\" title=\"Contents\" " +
                "filepos=0000000235 /></guide></head><body><p>Call me Ishmael.</p></body></html>",
        ))
        val chapter = File(root, "OEBPS/chapter1.xhtml").readText()
        assertEquals("one document, not one inside another: $chapter",
            1, Regex("(?i)<html\\b").findAll(chapter).count())
        assertEquals("and one body", 1, Regex("(?i)<body\\b").findAll(chapter).count())
        assertTrue("the words stay", chapter.contains("Call me Ishmael."))
        assertTrue("the paved paths a MOBI reader uses are gone",
            !chapter.contains("reference") && !chapter.contains("guide"))
    }

    @Test fun aChapterThatIsOnlyAnAdvertisementGetsAPlainName() {
        val root = convert(mobi(
            "<p>Call me Ishmael. Some years ago, never mind how long precisely, having little or no" +
                " money in my purse, I thought I would sail about a little.</p>" +
                "<mbp:pagebreak/>" +
                "<p> thwidth=\"<font size=\"7\"><b>读累了记得休息一会哦~</b></font></p>" +
                "<p><b>公众号：古德猫宁李</b></p>" +
                "<p><a href=\"https://www.chenjin5.com\">沉金书屋</a></p>",
        ))
        val nav = File(root, "OEBPS/nav.xhtml").readText()
        assertTrue("the advertisement does not name the chapter: $nav", nav.contains(">Section 2<"))
    }

    @Test fun anAzw3IsNamedRatherThanImportedAsAnEmptyBook() {
        val failure = runCatching { convert(mobi("<p>Words</p>", version = 8)) }.exceptionOrNull()
        assertTrue("it says what the file is: ${failure?.message}",
            failure?.message?.contains("AZW3") == true)
    }

    @Test fun aMobiWithNoTextIsRefusedRatherThanImportedEmpty() {
        val failure = runCatching { convert(mobi("<p>Words</p>", declaredRecords = 0)) }.exceptionOrNull()
        assertTrue("it says there is nothing to read: ${failure?.message}",
            failure?.message?.contains("no text") == true)
    }

    @Test fun aTextLengthLargerThanTheFileCouldHoldIsRefused() {
        val failure = runCatching { convert(mobi("<p>Words</p>", declaredLength = 900_000_000)) }
            .exceptionOrNull()
        assertTrue("it says the header is damaged rather than trying to hold it all: ${failure?.message}",
            failure?.message?.contains("damaged") == true)
    }

    @Test fun aProtectedBookIsRefusedInPlainWords() {
        val locked = mobi("<p>Words</p>").also { bytes ->
            val first = 78 // the first record's offset is at the head of the record table
            val start = ((bytes[first].toInt() and 0xFF) shl 24) or ((bytes[first + 1].toInt() and 0xFF) shl 16) or
                ((bytes[first + 2].toInt() and 0xFF) shl 8) or (bytes[first + 3].toInt() and 0xFF)
            bytes[start + 13] = 2 // PalmDOC's encryption field
        }
        val failure = runCatching { convert(locked) }.exceptionOrNull()
        assertTrue("it says the book is protected rather than failing obscurely",
            failure?.message?.contains("DRM-protected") == true)
    }

    @Test fun aBookCompressedWithADictionarySaysSoRatherThanHalfReading() {
        val failure = runCatching { convert(mobi("<p>Words</p>", compression = 17480)) }.exceptionOrNull()
        assertTrue("it names the compression it cannot read",
            failure?.message?.contains("Huffman") == true)
    }

    companion object {
        /** A one-pixel PNG, the way MOBI carries its pictures: as records of their own. */
        private val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
