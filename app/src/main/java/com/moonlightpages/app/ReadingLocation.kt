package com.moonlightpages.app

/** UTF-16 position in a sanitized chapter's text nodes; independent of page size. */
data class TextAnchor(val node: Int, val offset: Int, val quote: String = "")

data class ReadingLocation(
    val fraction: Float = 0f,
    val anchor: TextAnchor? = null,
    val start: TextAnchor? = null,
    val end: TextAnchor? = null,
    val page: Int = 0,
    val pages: Int = 1,
)

internal fun compareAnchors(first: TextAnchor, second: TextAnchor): Int =
    if (first.node == second.node) first.offset.compareTo(second.offset) else first.node.compareTo(second.node)

internal fun bookmarkOnPage(bookmarks: List<Bookmark>, chapter: Int, location: ReadingLocation): Bookmark? =
    bookmarks.firstOrNull { mark ->
        if (mark.chapter != chapter) false
        else if (mark.anchor != null && location.start != null && location.end != null) {
            compareAnchors(mark.anchor, location.start) >= 0 && compareAnchors(mark.anchor, location.end) < 0
        } else if (mark.anchor != null && location.anchor != null) {
            mark.anchor.node == location.anchor.node && mark.anchor.offset == location.anchor.offset
        } else kotlin.math.round(mark.position * (location.pages - 1)).toInt() == location.page
    }
