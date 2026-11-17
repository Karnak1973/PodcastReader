package com.podcastreader.domain.parser

import com.podcastreader.domain.model.Book
import java.io.InputStream

interface BookParser {
    fun supports(fileName: String, mimeType: String?): Boolean
    suspend fun parse(bookId: String, inputStream: InputStream): Book
}

class UnsupportedBookFormatException(message: String) : Exception(message)
