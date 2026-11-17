package com.podcastreader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<BookChunkEntity>)

    @Query(
        """
        SELECT * FROM book_chunks 
        WHERE bookId = :bookId 
          AND (
              chapterIndex < :currentChapter 
              OR (chapterIndex = :currentChapter AND startParagraphIndex <= :currentParagraph)
          )
        """
    )
    suspend fun getHeardChunksUpToPosition(
        bookId: String,
        currentChapter: Int,
        currentParagraph: Int
    ): List<BookChunkEntity>

    @Query(
        """
        SELECT textContent FROM book_chunks 
        WHERE bookId = :bookId AND chapterIndex = :targetChapter 
        ORDER BY chunkOrderInChapter ASC
        """
    )
    suspend fun getFullChapterText(bookId: String, targetChapter: Int): List<String>

    @Query("DELETE FROM book_chunks WHERE bookId = :bookId")
    suspend fun deleteChunksForBook(bookId: String)
}
