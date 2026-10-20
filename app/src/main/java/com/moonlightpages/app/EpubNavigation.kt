package com.moonlightpages.app

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URI

data class ChapterLink(val title: String, val chapter: Int, val fragment: String? = null, val depth: Int = 0)

/** Navigation is read from the imported files, so existing libraries need no migration. */
internal object EpubNavigation {
    fun contents(folder: File, chapters: List<String>): List<ChapterLink> {
        val declared = runCatching {
            val container = parser(File(folder, "META-INF/container.xml"))
            var packagePath: String? = null
            container.use { parsed ->
                val xml = parsed.xml
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name == "rootfile") {
                        packagePath = xml.getAttributeValue(null, "full-path")
                        break
                    }
                    xml.next()
                }
            }
            val opfPath = packagePath ?: return@runCatching emptyList()
            var nav: String? = null
            var ncx: String? = null
            parser(bookFile(folder, opfPath)).use { parsed ->
                val xml = parsed.xml
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name == "item") {
                        val href = xml.getAttributeValue(null, "href")
                        if (href != null) {
                            if (xml.getAttributeValue(null, "properties").orEmpty()
                                    .split(Regex("\\s+")).contains("nav")) {
                                nav = resolveBookPath(folder, opfPath, href)
                            }
                            if (xml.getAttributeValue(null, "media-type") == "application/x-dtbncx+xml") {
                                ncx = resolveBookPath(folder, opfPath, href)
                            }
                        }
                    }
                    xml.next()
                }
            }
            val modern = nav?.let { path -> runCatching { readNav(folder, path, chapters) }.getOrNull() }
            modern?.takeIf { it.isNotEmpty() }
                ?: ncx?.let { path -> runCatching { readNcx(folder, path, chapters) }.getOrNull() }
                ?: emptyList()
        }.getOrDefault(emptyList())
        // Keep every readable spine section accessible, including unlisted front/back matter.
        val unlisted = chapters.indices.filter { index -> declared.none { it.chapter == index } }
            .map { index -> ChapterLink(fallbackTitle(bookFile(folder, chapters[index]), index), index) }
        return (declared + unlisted).sortedBy { it.chapter }
    }

    private fun target(folder: File, parent: String, href: String, title: String,
                       chapters: List<String>, depth: Int): ChapterLink? = runCatching {
        val path = resolveBookPath(folder, parent, href)
        val index = chapters.indexOf(path)
        if (index < 0 || title.isBlank()) null
        else ChapterLink(title.trim().replace(Regex("\\s+"), " ").take(300), index,
            URI(href.replace(" ", "%20")).rawFragment?.takeIf { it.isNotEmpty() }, depth.coerceIn(0, 4))
    }.getOrNull()

    private fun readNav(folder: File, path: String, chapters: List<String>): List<ChapterLink> {
        val result = mutableListOf<ChapterLink>()
        parser(bookFile(folder, path)).use { parsed ->
            val xml = parsed.xml
            var tocDepth = -1
            var lists = 0
            while (xml.eventType != XmlPullParser.END_DOCUMENT && result.size < 2000) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    if (xml.name == "nav" && tocDepth < 0) {
                        val type = xml.getAttributeValue("http://www.idpf.org/2007/ops", "type").orEmpty()
                        if ("toc" in type.split(Regex("\\s+")) ||
                            xml.getAttributeValue(null, "role") == "doc-toc") tocDepth = xml.depth
                    } else if (tocDepth >= 0) {
                        if (xml.name == "ol") lists++
                        if (xml.name == "a") {
                            val href = xml.getAttributeValue(null, "href")
                            val title = text(xml)
                            if (href != null) target(folder, path, href, title, chapters, lists - 1)?.let { result.add(it) }
                        }
                    }
                } else if (xml.eventType == XmlPullParser.END_TAG && tocDepth >= 0) {
                    if (xml.name == "ol") lists--
                    if (xml.name == "nav" && xml.depth == tocDepth) break
                }
                xml.next()
            }
        }
        return result.distinctBy { it.chapter to it.fragment }
    }

    private data class NcxPoint(val order: Int, val depth: Int, var title: String = "", var href: String? = null)

    private fun readNcx(folder: File, path: String, chapters: List<String>): List<ChapterLink> {
        val stack = mutableListOf<NcxPoint>()
        val points = mutableListOf<NcxPoint>()
        parser(bookFile(folder, path)).use { parsed ->
            val xml = parsed.xml
            while (xml.eventType != XmlPullParser.END_DOCUMENT && points.size < 2000) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    when (xml.name) {
                        "navPoint" -> {
                            val point = NcxPoint(points.size, stack.size)
                            stack.add(point)
                            points.add(point)
                        }
                        "text" -> if (stack.isNotEmpty()) stack.last().title = text(xml)
                        "content" -> if (stack.isNotEmpty()) stack.last().href = xml.getAttributeValue(null, "src")
                    }
                } else if (xml.eventType == XmlPullParser.END_TAG && xml.name == "navPoint" && stack.isNotEmpty()) {
                    stack.removeAt(stack.lastIndex)
                }
                xml.next()
            }
        }
        return points.sortedBy { it.order }.mapNotNull { point ->
            point.href?.let { target(folder, path, it, point.title, chapters, point.depth) }
        }.distinctBy { it.chapter to it.fragment }
    }

    private fun text(xml: XmlPullParser): String {
        val depth = xml.depth
        val result = StringBuilder()
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.END_TAG && xml.depth == depth) break
            if (xml.eventType == XmlPullParser.TEXT && result.length < 1000) result.append(xml.text)
        }
        return result.toString()
    }

    private fun fallbackTitle(file: File, index: Int): String = runCatching {
        val start = file.bufferedReader().use { reader ->
            val buffer = CharArray(128 * 1024)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            String(buffer, 0, count)
        }
        val heading = Regex("<h[1-3]\\b[^>]*>(.*?)</h[1-3]\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val title = Regex("<title\\b[^>]*>(.*?)</title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val markup = (heading.find(start) ?: title.find(start))?.groupValues?.get(1).orEmpty()
        Html.fromHtml(markup, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            .replace(Regex("\\s+"), " ").take(300).ifBlank { "Section ${index + 1}" }
    }.getOrDefault("Section ${index + 1}")

    private class ParsedXml(val input: java.io.InputStream, val xml: XmlPullParser) : java.io.Closeable {
        override fun close() = input.close()
    }

    private fun parser(file: File): ParsedXml {
        require(file.isFile && file.length() <= 4L * 1024 * 1024) { "Invalid EPUB navigation." }
        val input = file.inputStream().buffered()
        return try {
            val xml = Xml.newPullParser()
            xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            xml.setInput(input, null)
            ParsedXml(input, xml)
        } catch (failure: Exception) { input.close(); throw failure }
    }
}
