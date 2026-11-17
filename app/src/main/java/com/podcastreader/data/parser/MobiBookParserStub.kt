package com.podcastreader.data.parser

import com.podcastreader.domain.model.Book
import com.podcastreader.domain.parser.BookParser
import com.podcastreader.domain.parser.UnsupportedBookFormatException
import java.io.InputStream

/**
 * Stub para soporte de archivos .mobi / .azw / .prc (Semana 2 del MVP).
 * La implementación definitiva integrará libmobi vía Android NDK (JNI).
 */
class MobiBookParserStub : BookParser {

    override fun supports(fileName: String, mimeType: String?): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".mobi") ||
            lower.endsWith(".azw") ||
            lower.endsWith(".prc") ||
            mimeType == "application/x-mobipocket-ebook"
    }

    override suspend fun parse(bookId: String, inputStream: InputStream): Book {
        throw UnsupportedBookFormatException(
            "El soporte para archivos MOBI está planificado para la Fase 2 (integración nativa libmobi). Por ahora importa un archivo .epub."
        )
    }
}
