package com.moonlightpages.app

enum class HighlightColor(val label: String, val hex: String) {
    YELLOW("Yellow", "#F2CC60"), MINT("Mint", "#8AC9A5"),
    ROSE("Rose", "#EDA4B5"), LAVENDER("Lavender", "#B9A7E4"),
}

data class SelectedPassage(
    val chapter: Int, val start: TextAnchor, val end: TextAnchor,
    val text: String, val fraction: Float,
    /** The book's own name for the chapter the passage sits in, empty when it names none. */
    val title: String = "",
)

/** A marked passage reads by the book's chapter name, or by its place in the file when unnamed. */
internal fun passageChapter(passage: SelectedPassage): String =
    passage.title.ifBlank { "Section ${passage.chapter + 1}" }

data class BookHighlight(
    val id: String, val passage: SelectedPassage, val color: HighlightColor, val createdAt: Long,
    /** The reader's own thought about this passage; empty when they only marked it. */
    val note: String = "",
) {
    val hasNote: Boolean get() = note.isNotBlank()
}

internal fun validPassage(passage: SelectedPassage): Boolean =
    passage.chapter >= 0 && passage.start.node >= 0 && passage.start.offset >= 0 &&
        passage.end.node >= 0 && passage.end.offset >= 0 &&
        compareAnchors(passage.start, passage.end) < 0 &&
        passage.text.isNotBlank() && passage.text.length <= 10_000 && passage.fraction.isFinite()

internal fun samePassage(first: SelectedPassage, second: SelectedPassage): Boolean =
    first.chapter == second.chapter && compareAnchors(first.start, second.start) == 0 &&
        compareAnchors(first.end, second.end) == 0 && first.text == second.text
