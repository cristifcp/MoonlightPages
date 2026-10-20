package com.moonlightpages.app

import android.net.Uri

/** A match inside one chapter. [node]/[offset] address the same text node the page script walks. */
internal data class SearchHit(
    val chapter: Int,
    val node: Int,
    val offset: Int,
    val quote: String,
    val before: String,
    val text: String,
    val after: String,
    val fraction: Float,
    /** The chapter's own name for the part of the book this hit sits in, empty when it names none. */
    val chapterTitle: String = "",
) {
    val anchor get() = TextAnchor(node, offset, quote)
}

/**
 * Whole-book text search. Chapters are sanitized once, on a background thread, using the
 * same markup the reader renders so a hit can be reopened as an ordinary text anchor.
 */
internal class BookSearch(private val store: EpubStore) {
    private data class Chapter(
        val nodes: List<String>, val lengths: IntArray, val total: Int, val headings: List<String>,
        /** Where the book's own chapters begin in this file: the text node each one reaches, and its name. */
        val sections: List<Pair<Int, String>>,
    )

    private val chapters = mutableMapOf<String, List<Chapter>>()

    fun search(book: Book, query: String, contents: List<ChapterLink> = emptyList(), limit: Int = 150): List<SearchHit> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        val hits = ArrayList<SearchHit>()
        for (chapter in book.chapters.indices) {
            val text = chapter(book, chapter, contents) ?: continue
            var before = 0
            for (node in text.nodes.indices) {
                val data = text.nodes[node]
                var from = 0
                while (true) {
                    val at = data.indexOf(needle, from, ignoreCase = true)
                    if (at < 0) break
                    hits.add(hit(chapter, node, at, needle.length, text, before))
                    if (hits.size >= limit) return hits
                    from = at + needle.length
                }
                before += text.lengths[node]
            }
        }
        return hits
    }

    private fun hit(chapter: Int, node: Int, at: Int, length: Int, text: Chapter, before: Int): SearchHit {
        val data = text.nodes[node]
        val from = (at - 48).coerceAtLeast(0)
        val to = (at + length + 72).coerceAtMost(data.length)
        return SearchHit(
            chapter = chapter, node = node, offset = at,
            quote = data.substring(at, (at + 48).coerceAtMost(data.length)),
            before = (if (from > 0) "…" else "") + data.substring(from, at).trimStart(),
            text = data.substring(at, at + length),
            after = data.substring(at + length, to).trimEnd() + (if (to < data.length) "…" else ""),
            fraction = if (text.total <= 0) 0f else ((before + at).toFloat() / text.total).coerceIn(0f, 1f),
            chapterTitle = sectionAt(text.sections, node) ?: text.headings.getOrElse(node) { "" },
        )
    }

    private fun chapter(book: Book, chapter: Int, contents: List<ChapterLink>): Chapter? = synchronized(chapters) {
        chapters.getOrPut(book.id) {
            val folder = store.folder(book)
            book.chapters.mapIndexed { index, path ->
                val links = contents.filter { it.chapter == index }
                val ids = links.mapNotNull { link -> link.fragment?.takeIf { it.isNotEmpty() }?.let { Uri.decode(it) } }.toSet()
                val parsed = runCatching { ChapterHtml.chapterText(bookFile(folder, path).readText(), ids) }
                    .getOrDefault(ChapterText(emptyList(), emptyList(), emptyMap()))
                // A link with no fragment is the start of the file; one with a fragment starts where its anchor is.
                val sections = links.mapNotNull { link ->
                    val fragment = link.fragment?.takeIf { it.isNotEmpty() }
                    if (fragment == null) 0 to link.title
                    else parsed.anchors[Uri.decode(fragment)]?.let { it to link.title }
                }.sortedBy { it.first }
                Chapter(parsed.nodes, IntArray(parsed.nodes.size) { parsed.nodes[it].length },
                    parsed.nodes.sumOf { it.length }, parsed.headings, sections)
            }
        }.getOrNull(chapter)
    }
}

/** The name of the book's own chapter a text node falls in: the last one that begins at or before it. */
internal fun sectionAt(sections: List<Pair<Int, String>>, node: Int): String? =
    sections.lastOrNull { it.first <= node }?.second
