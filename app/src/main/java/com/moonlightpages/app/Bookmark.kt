package com.moonlightpages.app

import kotlin.math.abs

data class Bookmark(
    val id: String,
    val chapter: Int,
    val position: Float,
    val title: String,
    val createdAt: Long,
    val anchor: TextAnchor? = null,
)

internal fun bookmarkAt(bookmarks: List<Bookmark>, chapter: Int, position: Float): Bookmark? =
    bookmarks.firstOrNull { it.chapter == chapter && abs(it.position - position) < .002f }
