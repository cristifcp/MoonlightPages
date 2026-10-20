package com.moonlightpages.app

/**
 * How many chapters the book itself names inside each of its sections.
 *
 * A printed novel is not stored one chapter per file: Gutenberg's Moby Dick packs about ten chapters
 * into every file. Counting files therefore moves the shelf bar in great jumps and calls seven
 * chapters "this chapter". Counting the names the book gives its own chapters keeps a position
 * meaning the same share of the book, whichever way the EPUB happens to be packaged.
 */
internal class ChapterPlan private constructor(
    private val counts: IntArray, private val before: IntArray, val total: Int,
) {
    /** The reader's share of the book: chapters already behind them, plus this one's progress. */
    fun progress(chapter: Int, fraction: Float): Float {
        if (total <= 0 || chapter !in counts.indices) return 0f
        val share = before[chapter] + fraction.coerceIn(0f, 1f) * counts[chapter]
        return (share / total).coerceIn(0f, 1f)
    }

    companion object {
        /**
         * Reads the plan out of a book's own table of contents. Every section contributes at least
         * one entry there, so a plan always ends exactly where the old file count did; it only ever
         * divides the way there more finely. Null when the book names nothing usable.
         */
        fun of(links: List<ChapterLink>, sections: Int): ChapterPlan? {
            if (sections <= 0) return null
            val counts = IntArray(sections)
            for (link in links) if (link.chapter in 0 until sections) counts[link.chapter]++
            val total = counts.sum()
            if (total < sections) return null
            val before = IntArray(sections)
            var running = 0
            for (index in 0 until sections) {
                before[index] = running
                running += counts[index]
            }
            return ChapterPlan(counts, before, total)
        }
    }
}

/** A chapter the book names, on the page its own anchor puts it. */
internal data class NamedChapter(val title: String, val page: Int)

/** The named chapter the reader is in, or null when this file's anchors could not be placed. */
internal fun namedChapterAt(marks: List<NamedChapter>, page: Int): NamedChapter? =
    marks.lastOrNull { it.page <= page } ?: marks.firstOrNull()

/**
 * Pages left before the next named chapter starts. Null when the anchors could not be placed or this
 * file holds no later chapter, so the reader falls back to counting to the end of the file.
 */
internal fun pagesLeftInChapter(marks: List<NamedChapter>, page: Int): Int? =
    marks.asSequence().map { it.page }.filter { it > page }.minOrNull()?.let { (it - page - 1).coerceAtLeast(0) }

/**
 * The page the named chapter holding [page] starts on, and the page it ends on: [lastPage] when no
 * later chapter is named in this file. Null when the anchors could not be placed, so the reader
 * falls back to counting the whole file. A file can hold several of the book's chapters, so counting
 * the chapter is what makes a page number mean the same thing wherever the book is opened.
 */
internal fun chapterPageSpan(marks: List<NamedChapter>, page: Int, lastPage: Int): Pair<Int, Int>? {
    val start = namedChapterAt(marks, page)?.page ?: return null
    // The page before the next chapter begins, or the file's own last page when none is named.
    val end = marks.asSequence().map { it.page }.filter { it > start }.minOrNull()?.minus(1) ?: lastPage
    return start to end.coerceAtLeast(start)
}
