package com.moonlightpages.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Book scripts never run. Only our bundled pagination code is evaluated by the app. */
/** A chapter's text: each node, the heading it sits under, and the text node each anchor reaches first. */
internal data class ChapterText(val nodes: List<String>, val headings: List<String>, val anchors: Map<String, Int>)

internal object ChapterHtml {
    /** A sanitized chapter plus the book stylesheets, which are hoisted into the page head. */
    private data class Sanitized(val styles: String, val body: String)

    /**
     * Sanitized chapter body. This is the exact markup the reader renders inside its page
     * container, so anything derived from it lines up with what the page script sees.
     */
    fun body(source: String): String = sanitize(source).body

    fun prepare(
        source: String, fontSize: Int, paper: Paper,
        spacing: Int = ReadingTypography.defaultSpacing,
        inset: Int = ReadingTypography.defaultInset,
        font: Int = ReadingTypography.defaultFont,
    ): String = page(sanitize(source), fontSize, paper, spacing, inset, font)

    /**
     * Visible text of a chapter, one entry per text node, in document order.
     * The page script walks the rendered chapter the same way, so an index here
     * refers to the same node there. Blank nodes are skipped by both.
     */
    fun textNodes(source: String): List<String> = textNodesOf(Jsoup.parse(page(sanitize(source), 16, papers[0])))

    /**
     * The chapter's visible text, and for every one of its nodes the heading that node sits under:
     * the name the book gives that part of the chapter. A file can hold several of the book's
     * chapters, so this is what lets a search hit say which one it was found in.
     */
    fun textWithHeadings(source: String): Pair<List<String>, List<String>> =
        chapterText(source).let { it.nodes to it.headings }

    /**
     * The chapter's text, with the heading each node sits under and where each of [anchors] (element
     * ids) is reached: the index of the first text node that comes after it.
     */
    internal fun chapterText(source: String, anchors: Set<String> = emptySet()): ChapterText =
        textAndHeadingsOf(Jsoup.parse(page(sanitize(source), 16, papers[0])), anchors)

    internal fun textNodesOf(document: Document): List<String> = textAndHeadingsOf(document, emptySet()).nodes

    private fun textAndHeadingsOf(document: Document, anchors: Set<String>): ChapterText {
        val root = document.selectFirst("#__moonlightPages") ?: return ChapterText(emptyList(), emptyList(), emptyMap())
        val nodes = mutableListOf<String>()
        val headings = mutableListOf<String>()
        val reached = mutableMapOf<String, Int>()
        var heading = ""
        fun visit(node: Node) {
            if (node is TextNode) {
                val data = node.wholeText
                if (data.isNotBlank()) {
                    nodes.add(data)
                    headings.add(heading)
                }
            } else {
                if (node is Element) {
                    if (node.tag().name.lowercase() in HEADINGS) {
                        heading = node.wholeText().replace(Regex("\\s+"), " ").trim().take(120)
                    }
                    if (node.id() in anchors && node.id() !in reached) reached[node.id()] = nodes.size
                }
                node.childNodes().forEach(::visit)
            }
        }
        root.childNodes().forEach(::visit)
        return ChapterText(nodes, headings, reached)
    }

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private fun sanitize(source: String): Sanitized {
        val document = Jsoup.parse(source)
        document.select("script, iframe, frame, frameset, object, embed, applet, base, meta, form, input, button, textarea, select, audio, video, source, foreignObject, animate, set").remove()
        document.getAllElements().forEach { element ->
            element.attributes().asList().forEach { attribute ->
                val key = attribute.key.lowercase()
                if (key.startsWith("on") || key in setOf("srcdoc", "nonce", "autofocus", "contenteditable")) element.removeAttr(attribute.key)
                if (key in setOf("href", "src", "xlink:href") &&
                    attribute.value.trim().lowercase().let { it.startsWith("javascript:") || it.startsWith("vbscript:") }) {
                    element.removeAttr(attribute.key)
                }
            }
            if (element.id().startsWith("__moonlight")) element.removeAttr("id")
        }
        // Lazy images otherwise change the column layout when their page first becomes visible.
        document.select("img").forEach { image ->
            image.attr("loading", "eager")
            image.removeAttr("autofocus")
        }
        val styles = document.select("style, link[rel=stylesheet]").joinToString("\n") { it.outerHtml() }
        document.select("style, link").remove()
        return Sanitized(styles, document.body().html())
    }

    private fun page(
        chapter: Sanitized, fontSize: Int, paper: Paper,
        spacing: Int = ReadingTypography.defaultSpacing,
        inset: Int = ReadingTypography.defaultInset,
        font: Int = ReadingTypography.defaultFont,
    ): String {
        // The page keeps a margin at its sides and a slightly smaller one above and below.
        val room = ReadingTypography.inset(inset)
        val tall = ReadingTypography.verticalInset(room)
        val lineHeight = ReadingTypography.spacing(spacing) / 100.0
        val face = ReadingTypography.font(font)
        return """<!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            ${chapter.styles}
            <style>
            html, body { width:100% !important; height:100% !important; margin:0 !important; padding:0 !important;
                overflow:hidden !important; overscroll-behavior:none; background:${paper.background} !important;
                color:${paper.ink} !important; -webkit-text-size-adjust:100%; }
            #__moonlightViewport { position:fixed !important; inset:0 !important; overflow:hidden !important;
                contain:strict; isolation:isolate; }
            #__moonlightPages { position:absolute !important; left:${room}px !important; top:${tall}px !important;
                width:calc(100vw - ${room * 2}px) !important; height:calc(100vh - ${tall * 2}px) !important;
                margin:0 !important; padding:0 !important; border:0 !important;
                column-width:calc(100vw - ${room * 2}px) !important; column-gap:${room * 2}px !important;
                column-fill:auto !important; column-count:auto !important;
                font-family:$face !important; font-size:${fontSize}px !important;
                line-height:$lineHeight !important; color:${paper.ink} !important; background:transparent !important;
                overflow:visible !important; overflow-wrap:anywhere; direction:ltr !important; writing-mode:horizontal-tb !important; }
            #__moonlightPages * { max-width:100% !important; box-sizing:border-box; position:static !important;
                transform:none !important; float:none !important; overflow-wrap:anywhere; }
            #__moonlightPages p, #__moonlightPages li, #__moonlightPages blockquote {
                font-size:inherit !important; line-height:inherit !important; color:inherit !important; }
            #__moonlightPages p { margin:0 0 1em; orphans:2; widows:2; }
            #__moonlightPages h1, #__moonlightPages h2, #__moonlightPages h3 {
                line-height:1.2; break-after:avoid; color:inherit !important; }
            #__moonlightPages img, #__moonlightPages svg { max-width:100% !important;
                max-height:calc(100vh - ${tall * 2}px) !important; width:auto; height:auto !important;
                object-fit:contain; break-inside:avoid; }
            #__moonlightPages table { width:100% !important; table-layout:fixed; }
            #__moonlightPages pre { white-space:pre-wrap !important; }
            #__moonlightPages a { color:inherit; }
            </style></head><body><div id="__moonlightViewport"><main id="__moonlightPages">${chapter.body}</main></div></body></html>"""
    }
}
