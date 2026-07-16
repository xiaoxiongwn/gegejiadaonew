package com.example.searchfloat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {
    @Insert
    suspend fun insert(question: Question): Long

    @Update
    suspend fun update(question: Question)

    @Delete
    suspend fun delete(question: Question)

    @Query("SELECT * FROM questions ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Question>>

    @Query("SELECT * FROM questions WHERE library = :library ORDER BY createdAt DESC")
    fun getAllByLibrary(library: String): Flow<List<Question>>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: Long): Question?

    @Query("SELECT * FROM questions WHERE library = :library AND (title LIKE '%' || :keyword || '%' OR content LIKE '%' || :keyword || '%' OR category LIKE '%' || :keyword || '%') ORDER BY createdAt DESC")
    suspend fun searchInLibrary(library: String, keyword: String): List<Question>

    @Query("SELECT * FROM questions")
    suspend fun getAllOnce(): List<Question>

    @Query("SELECT * FROM questions WHERE library = :library")
    suspend fun getAllOnceByLibrary(library: String): List<Question>

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM questions WHERE library = :library")
    suspend fun countByLibrary(library: String): Int

    @Query("SELECT DISTINCT library FROM questions ORDER BY library")
    suspend fun getLibraries(): List<String>

    @Query("SELECT DISTINCT library FROM questions ORDER BY library")
    fun getLibrariesFlow(): Flow<List<String>>

    @Query("DELETE FROM questions WHERE library = :library")
    suspend fun deleteLibrary(library: String)

    @Query("UPDATE questions SET library = :newName WHERE library = :oldName")
    suspend fun renameLibrary(oldName: String, newName: String)

    @Query("SELECT * FROM questions WHERE category = :category ORDER BY createdAt DESC")
    fun getByCategory(category: String): Flow<List<Question>>

    // 兼容旧调用
    @Query("SELECT * FROM questions WHERE (title LIKE '%' || :keyword || '%' OR content LIKE '%' || :keyword || '%' OR category LIKE '%' || :keyword || '%') ORDER BY createdAt DESC")
    suspend fun search(keyword: String): List<Question>

    // ====== 练习 ======
    @Query("SELECT * FROM questions WHERE library = :library ORDER BY id ASC")
    suspend fun getOrderedByLibrary(library: String): List<Question>

    @Query("SELECT * FROM questions WHERE library = :library AND category = :category ORDER BY id ASC")
    suspend fun getByLibraryAndCategory(library: String, category: String): List<Question>

    @Query("SELECT DISTINCT category FROM questions WHERE library = :library AND category != '' ORDER BY category")
    suspend fun getCategoriesByLibrary(library: String): List<String>

    // ====== 错题 ======
    @Insert
    suspend fun insertWrong(wrong: WrongAnswer): Long

    @Query("DELETE FROM wrong_answers WHERE questionId = :qid")
    suspend fun clearWrongOf(qid: Long)

    @Query("DELETE FROM wrong_answers WHERE library = :library")
    suspend fun clearAllWrongOfLibrary(library: String)

    @Query("SELECT q.* FROM questions q INNER JOIN wrong_answers w ON q.id = w.questionId WHERE q.library = :library GROUP BY q.id ORDER BY MAX(w.createdAt) DESC")
    suspend fun getWrongQuestions(library: String): List<Question>

    @Query("SELECT COUNT(DISTINCT questionId) FROM wrong_answers WHERE library = :library")
    suspend fun countWrong(library: String): Int

    @Query("SELECT COUNT(DISTINCT questionId) FROM wrong_answers WHERE library = :library")
    fun countWrongFlow(library: String): Flow<Int>
}
