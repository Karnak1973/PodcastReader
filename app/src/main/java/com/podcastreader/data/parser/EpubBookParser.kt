package com.podcastreader.data.parser

import com.podcastreader.domain.model.Book
import com.podcastreader.domain.model.Chapter
import com.podcastreader.domain.model.Paragraph
import com.podcastreader.domain.model.TocItem
import com.podcastreader.domain.parser.BookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class EpubBookParser : BookParser {

    override fun supports(fileName: String, mimeType: String?): Boolean {
        return fileName.endsWith(".epub", ignoreCase = true) ||
            mimeType == "application/epub+zip"
    }

    override suspend fun parse(bookId: String, inputStream: InputStream): Book =
        withContext(Dispatchers.IO) {
            val entries = readZipEntries(inputStream)

            val containerBytes = entries["META-INF/container.xml"]
                ?: error("Archivo EPUB inválido: falta META-INF/container.xml")
            val opfPath = extractOpfPath(containerBytes)
                ?: error("Archivo EPUB inválido: no se encontró el archivo OPF")

            val opfBytes = entries[opfPath]
                ?: error("Archivo EPUB inválido: no existe $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val opfDoc = Jsoup.parse(
                String(opfBytes, StandardCharsets.UTF_8),
                "",
                Parser.xmlParser()
            )

            val title = opfDoc.selectFirst("metadata > title, metadata > dc|title, title")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: "Libro sin título"
            val author = opfDoc.selectFirst("metadata > creator, metadata > dc|creator, creator")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: "Autor desconocido"

            val manifestById = mutableMapOf<String, ManifestItem>()
            opfDoc.select("manifest > item").forEach { item ->
                val id = item.attr("id")
                val href = decodePath(item.attr("href"))
                val mediaType = item.attr("media-type")
                val properties = item.attr("properties")
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifestById[id] = ManifestItem(
                        id = id,
                        resolvedPath = resolveRelativePath(opfDir, href),
                        mediaType = mediaType,
                        properties = properties
                    )
                }
            }

            val tocTitlesByPath = extractTocTitles(entries, manifestById.values, opfDir)

            val spineIds = opfDoc.select("spine > itemref")
                .mapNotNull { it.attr("idref").takeIf(String::isNotBlank) }

            val chapters = mutableListOf<Chapter>()
            val tocItems = mutableListOf<TocItem>()

            spineIds.forEach { idref ->
                val item = manifestById[idref] ?: return@forEach
                val htmlBytes = entries[item.resolvedPath] ?: return@forEach
                val html = String(htmlBytes, StandardCharsets.UTF_8)

                val extracted = extractChapterContent(html)
                if (extracted.paragraphs.isEmpty()) return@forEach

                val chapterIdx = chapters.size
                val chapterTitle = tocTitlesByPath[item.resolvedPath]
                    ?: extracted.headingTitle
                    ?: "Capítulo ${chapterIdx + 1}"

                val paragraphs = extracted.paragraphs.mapIndexed { pIdx, text ->
                    Paragraph(index = pIdx, text = text)
                }

                chapters.add(
                    Chapter(
                        index = chapterIdx,
                        id = idref,
                        title = chapterTitle,
                        paragraphs = paragraphs
                    )
                )
                tocItems.add(
                    TocItem(
                        title = chapterTitle,
                        chapterIndex = chapterIdx,
                        level = 0
                    )
                )
            }

            val coverBytes = extractCoverBytes(opfDoc, manifestById, entries)

            Book(
                id = bookId,
                title = title,
                author = author,
                chapters = chapters,
                toc = tocItems,
                coverImageBytes = coverBytes
            )
        }

    private fun readZipEntries(inputStream: InputStream): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    var len: Int
                    while (zis.read(buffer).also { len = it } != -1) {
                        out.write(buffer, 0, len)
                    }
                    entries[entry.name.replace("\\", "/")] = out.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }

    private fun extractCoverBytes(
        opfDoc: org.jsoup.nodes.Document,
        manifestById: Map<String, ManifestItem>,
        entries: Map<String, ByteArray>
    ): ByteArray? {
        // 1. EPUB 3: properties="cover-image"
        val epub3Cover = manifestById.values.firstOrNull {
            it.properties.split(" ").contains("cover-image")
        }
        if (epub3Cover != null) {
            entries[epub3Cover.resolvedPath]?.let { return it }
        }

        // 2. EPUB 2: <meta name="cover" content="item-id"/>
        val coverId = opfDoc.selectFirst("metadata > meta[name=cover]")
            ?.attr("content")?.trim()
        if (!coverId.isNullOrEmpty()) {
            manifestById[coverId]?.let { item ->
                entries[item.resolvedPath]?.let { return it }
            }
        }

        // 3. Fallback: manifest image item with "cover" in id or path
        val fallbackCover = manifestById.values.firstOrNull { item ->
            item.mediaType.startsWith("image/", ignoreCase = true) &&
                (item.id.contains("cover", ignoreCase = true) ||
                    item.resolvedPath.contains("cover", ignoreCase = true))
        }
        return fallbackCover?.let { entries[it.resolvedPath] }
    }

    private fun extractOpfPath(containerBytes: ByteArray): String? {
        val doc = Jsoup.parse(
            String(containerBytes, StandardCharsets.UTF_8),
            "",
            Parser.xmlParser()
        )
        return doc.selectFirst("rootfile")?.attr("full-path")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractTocTitles(
        entries: Map<String, ByteArray>,
        manifestItems: Collection<ManifestItem>,
        opfDir: String
    ): Map<String, String> {
        val titles = mutableMapOf<String, String>()

        // 1. Try EPUB3 NAV document
        val navItem = manifestItems.firstOrNull {
            it.properties.split(" ").contains("nav")
        }
        if (navItem != null) {
            entries[navItem.resolvedPath]?.let { navBytes ->
                val navDir = navItem.resolvedPath.substringBeforeLast('/', "")
                val navDoc = Jsoup.parse(String(navBytes, StandardCharsets.UTF_8))
                navDoc.select("nav a[href]").forEach { link ->
                    val rawHref = link.attr("href").substringBefore('#')
                    val resolved = resolveRelativePath(navDir, decodePath(rawHref))
                    val label = link.text().trim()
                    if (label.isNotEmpty() && !titles.containsKey(resolved)) {
                        titles[resolved] = label
                    }
                }
            }
        }

        // 2. Fallback or supplement with NCX (EPUB2)
        val ncxItem = manifestItems.firstOrNull {
            it.mediaType == "application/x-dtbncx+xml" || it.resolvedPath.endsWith(".ncx", true)
        }
        if (ncxItem != null) {
            entries[ncxItem.resolvedPath]?.let { ncxBytes ->
                val ncxDir = ncxItem.resolvedPath.substringBeforeLast('/', "")
                val ncxDoc = Jsoup.parse(
                    String(ncxBytes, StandardCharsets.UTF_8),
                    "",
                    Parser.xmlParser()
                )
                ncxDoc.select("navPoint").forEach { navPoint ->
                    val label = navPoint.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                    val src = navPoint.selectFirst("content")?.attr("src")
                        ?.substringBefore('#')?.let(::decodePath).orEmpty()
                    if (label.isNotEmpty() && src.isNotEmpty()) {
                        val resolved = resolveRelativePath(ncxDir, src)
                        titles.putIfAbsent(resolved, label)
                    }
                }
            }
        }

        return titles
    }

    internal fun extractChapterContent(html: String): ExtractedChapter {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, sup.footnote, a[epub|type=noteref], [role=doc-noteref]")
            .remove()

        val headingTitle = doc.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val blockElements = doc.body().select("h1, h2, h3, h4, p, li, blockquote")
        val rawParagraphs = if (blockElements.isNotEmpty()) {
            blockElements
                .filter { el -> el.parents().none { it in blockElements } }
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
        } else {
            doc.body().text()
                .split(Regex("\\n+"))
                .map { it.replace(Regex("\\s+"), " ").trim() }
        }

        val cleanParagraphs = rawParagraphs.filter { it.isNotBlank() }
        return ExtractedChapter(headingTitle = headingTitle, paragraphs = cleanParagraphs)
    }

    private fun resolveRelativePath(baseDir: String, relative: String): String {
        val combined = if (baseDir.isEmpty()) relative else "$baseDir/$relative"
        val parts = combined.split("/")
        val stack = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun decodePath(path: String): String {
        return try {
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            path
        }
    }

    private data class ManifestItem(
        val id: String,
        val resolvedPath: String,
        val mediaType: String,
        val properties: String
    )

    internal data class ExtractedChapter(
        val headingTitle: String?,
        val paragraphs: List<String>
    )
}
