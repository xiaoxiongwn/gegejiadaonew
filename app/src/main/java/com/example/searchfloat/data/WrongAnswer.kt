package com.example.searchfloat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 错题记录。每答错一次插入一条；同一题可能有多条历史。
 * 错题练习时按题目去重 + 按最近一次错题时间倒排。
 */
@Entity(
    tableName = "wrong_answers",
    indices = [Index("questionId"), Index("library")]
)
data class WrongAnswer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionId: Long,
    val library: String,
    val userAnswer: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
