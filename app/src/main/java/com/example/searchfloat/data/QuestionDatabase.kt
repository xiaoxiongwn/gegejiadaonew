package com.example.searchfloat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Question::class, WrongAnswer::class], version = 3)
abstract class QuestionDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao

    companion object {
        @Volatile
        private var INSTANCE: QuestionDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN library TEXT NOT NULL DEFAULT '默认题库'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS wrong_answers (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      questionId INTEGER NOT NULL,
                      library TEXT NOT NULL,
                      userAnswer TEXT NOT NULL DEFAULT '',
                      createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wrong_answers_questionId ON wrong_answers(questionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wrong_answers_library ON wrong_answers(library)")
            }
        }

        fun getDatabase(context: Context): QuestionDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QuestionDatabase::class.java,
                    "question_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
