package com.podcastreader

import com.podcastreader.data.parser.EpubBookParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookParserTest {

    private val parser = EpubBookParser()

    @Test
    fun `extractChapterContent removes noise tags and splits clean paragraphs`() {
        val html = """
            <html>
              <head><style>.x{color:red}</style><script>alert(1)</script></head>
              <body>
                <nav><a href="#1">Skip nav</a></nav>
                <h1>Capítulo Uno</h1>
                <p>Primer párrafo con   espacios   extra.<sup class="footnote">1</sup></p>
                <p>Segundo párrafo limpio.</p>
              </body>
            </html>
        """.trimIndent()

        val result = parser.extractChapterContent(html)
        assertEquals("Capítulo Uno", result.headingTitle)
        assertEquals(
            listOf(
                "Capítulo Uno",
                "Primer párrafo con espacios extra.",
                "Segundo párrafo limpio."
            ),
            result.paragraphs
        )
    }

    @Test
    fun `parse reads OPF metadata, NCX TOC, and spine chapters from EPUB zip`() = runTest {
        val epubBytes = buildMinimalEpub()
        val book = parser.parse("book_1", ByteArrayInputStream(epubBytes))

        assertEquals("El Viaje", book.title)
        assertEquals("Autor Prueba", book.author)
        assertEquals(2, book.chapters.size)
        assertEquals("Inicio", book.toc[0].title)
        assertEquals("Desenlace", book.toc[1].title)
        assertTrue(book.chapters[0].paragraphs.any { it.text.contains("Había una vez") })
        assertTrue(book.chapters[1].paragraphs.any { it.text.contains("Fin del viaje") })
    }

    private fun buildMinimalEpub(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }

            add("mimetype", "application/epub+zip")
            add(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent()
            )
            add(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>El Viaje</dc:title>
                    <dc:creator>Autor Prueba</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
                """.trimIndent()
            )
            add(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="np1" playOrder="1">
                      <navLabel><text>Inicio</text></navLabel>
                      <content src="ch1.xhtml"/>
                    </navPoint>
                    <navPoint id="np2" playOrder="2">
                      <navLabel><text>Desenlace</text></navLabel>
                      <content src="ch2.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
                """.trimIndent()
            )
            add("OEBPS/ch1.xhtml", "<html><body><p>Había una vez un lector.</p></body></html>")
            add("OEBPS/ch2.xhtml", "<html><body><p>Fin del viaje.</p></body></html>")
        }
        return out.toByteArray()
    }
}
