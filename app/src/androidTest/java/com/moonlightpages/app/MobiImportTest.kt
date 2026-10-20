package com.moonlightpages.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * A MOBI built by hand, imported the way the app imports one, and read back through the reader's own
 * package and contents: the whole path, on the device.
 */
class MobiImportTest {
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "mobi-import-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
    }

    @After fun cleanup() {
        context.getSharedPreferences("reading", Context.MODE_PRIVATE).edit().clear().commit()
        folder.deleteRecursively()
    }

    @Test fun aMobiCanBeImportedAndRead() {
        val book = store.importBook(ByteArrayInputStream(mobi()))

        assertEquals("the name the file gives itself", "Loomings", book.title)
        assertEquals("one chapter per page break, and the notes with them", 3, book.chapters.size)
        book.chapters.forEach { require(bookFile(store.folder(book), it).isFile) { "missing chapter $it" } }
        assertEquals("the reader can name every part of it",
            listOf("Chapter One", "Chapter Two", "Notes"), store.contents(book).map { it.title })
        assertTrue("and the words survive the compression",
            bookFile(store.folder(book), "OEBPS/chapter1.xhtml").readText().contains("Call me Ishmael."))
    }

    /** A small MOBI: a Palm database, PalmDOC-compressed text, and a name of its own. */
    private fun mobi(): ByteArray {
        val text = ("<h1>Chapter One</h1><p>Call me Ishmael.</p><mbp:pagebreak/>" +
            "<h1>Chapter Two</h1><p>The carpet bag.</p><mbp:pagebreak/>" +
            "<h1>Notes</h1><p>A note about the bag.</p>").toByteArray(Charsets.UTF_8)
        val records = listOf(header(text.size), literal(text))
        val head = ByteArray(78 + records.size * 8)
        "Loomings".toByteArray().copyInto(head, 0)
        be16(head, 76, records.size)
        var at = head.size + 2
        records.forEachIndexed { index, record -> be32(head, 78 + index * 8, at); at += record.size }
        val out = ByteArray(at)
        head.copyInto(out)
        var writing = head.size + 2
        records.forEach { record -> record.copyInto(out, writing); writing += record.size }
        "BOOKMOBI".forEachIndexed { index, c -> out[60 + index] = c.code.toByte() }
        return out
    }

    private fun header(length: Int): ByteArray {
        val record = ByteArray(16 + 0xE4)
        be16(record, 0, 2)
        be32(record, 4, length)
        be16(record, 8, 1)
        be16(record, 10, 4096)
        "MOBI".forEachIndexed { index, c -> record[16 + index] = c.code.toByte() }
        le32(record, 16 + 4, 0xE4)
        le32(record, 16 + 12, 65001)
        le32(record, 16 + 0x5C, 0)
        le32(record, 16 + 0x70, 0)
        return record
    }

    /** PalmDOC's simplest form: every byte written as a literal. */
    private fun literal(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (value in bytes) {
            val code = value.toInt() and 0xFF
            if (code == 0) out.write(0) else if (code <= 0x08) { out.write(1); out.write(code) }
            else if (code <= 0x7F) out.write(code) else { out.write(1); out.write(code) }
        }
        return out.toByteArray()
    }

    private fun be16(target: ByteArray, at: Int, value: Int) {
        target[at] = (value shr 8).toByte(); target[at + 1] = value.toByte()
    }

    private fun be32(target: ByteArray, at: Int, value: Int) {
        target[at] = (value shr 24).toByte(); target[at + 1] = (value shr 16).toByte()
        target[at + 2] = (value shr 8).toByte(); target[at + 3] = value.toByte()
    }

    private fun le32(target: ByteArray, at: Int, value: Int) {
        target[at] = value.toByte(); target[at + 1] = (value shr 8).toByte()
        target[at + 2] = (value shr 16).toByte(); target[at + 3] = (value shr 24).toByte()
    }
}
