package com.example.searchfloat.util

import android.content.Context

object ActiveLibrary {
    private const val PREFS = "search_float_prefs"
    private const val KEY = "active_library"
    const val DEFAULT = "默认题库"

    @Volatile private var listeners: MutableList<(String) -> Unit> = mutableListOf()

    fun get(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT) ?: DEFAULT
    }

    fun set(context: Context, name: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, name).apply()
        synchronized(listeners) {
            listeners.toList()
        }.forEach { it(name) }
    }

    fun addListener(cb: (String) -> Unit) {
        synchronized(listeners) { listeners.add(cb) }
    }

    fun removeListener(cb: (String) -> Unit) {
        synchronized(listeners) { listeners.remove(cb) }
    }
}
