package com.podcastreader.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Entity(
    tableName = "book_chunks",
    indices = [
        Index(value = ["bookId", "chapterIndex"])
    ]
)
data class BookChunkEntity(
    @PrimaryKey val chunkId: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val chunkOrderInChapter: Int,
    val startParagraphIndex: Int,
    val endParagraphIndex: Int,
    val textContent: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BookChunkEntity) return false
        return chunkId == other.chunkId &&
            bookId == other.bookId &&
            chapterIndex == other.chapterIndex &&
            chunkOrderInChapter == other.chunkOrderInChapter &&
            startParagraphIndex == other.startParagraphIndex &&
            endParagraphIndex == other.endParagraphIndex &&
            textContent == other.textContent &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + bookId.hashCode()
        result = 31 * result + chapterIndex
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

class EmbeddingConverters {
    @TypeConverter
    fun fromFloatArray(array: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(array)
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray): FloatArray {
        val floatBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val array = FloatArray(floatBuffer.remaining())
        floatBuffer.get(array)
        return array
    }
}
