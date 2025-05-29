package com.yourcompany.wordlearner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "words")
data class Word(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val text: String,
    val audioFilePath: String // Path to local MP3 file
)