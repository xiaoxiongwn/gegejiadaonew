package com.example.searchfloat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "questions")
data class Question(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val category: String = "",
    val library: String = "默认题库",
    val createdAt: Long = System.currentTimeMillis()
)
