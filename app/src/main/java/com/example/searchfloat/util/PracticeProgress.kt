package com.example.searchfloat.util

import android.content.Context

/**
 * 练习进度持久化：按 (题库 + 模式 + 题型) 组合记录上次练到第几题。
 */
object PracticeProgress {
    private const val PREFS = "practice_progress"

    private fun key(library: String, mode: String, type: String?): String =
        "${library}__${mode}__${type ?: ""}"

    fun get(context: Context, library: String, mode: String, type: String?): Int {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(key(library, mode, type), 0)
            .coerceAtLeast(0)
    }

    fun set(context: Context, library: String, mode: String, type: String?, idx: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key(library, mode, type), idx.coerceAtLeast(0)).apply()
    }

    fun clear(context: Context, library: String, mode: String, type: String?) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(library, mode, type)).apply()
    }
}
