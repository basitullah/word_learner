package com.yourcompany.wordlearner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {
    @Insert
    suspend fun insertWord(word: Word)

    @Update
    suspend fun updateWord(word: Word)

    @Query("SELECT * FROM words WHERE audioFilePath IS NOT NULL AND audioFilePath != ''")
    suspend fun getWordsWithAudio(): List<Word>

    @Query("SELECT COUNT(*) FROM words WHERE audioFilePath IS NOT NULL AND audioFilePath != ''")
    suspend fun countWordsWithAudio(): Int

    @Delete
    suspend fun deleteWord(word: Word)

    @Query("SELECT * FROM words ORDER BY text ASC")
    fun getAllWords(): Flow<List<Word>>

    @Query("SELECT * FROM words ORDER BY RANDOM() LIMIT :count")
    suspend fun getRandomWords(count: Int): List<Word>

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun getWordById(id: Int): Word?
}