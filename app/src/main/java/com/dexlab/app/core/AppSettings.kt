package com.dexlab.app.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppSettings {
    private const val PREFS = "dexlab_settings"
    private var prefs: SharedPreferences? = null

    var themeMode by mutableStateOf("dark")
        private set
    var fontSize by mutableIntStateOf(12)
        private set
    var wrapLines by mutableStateOf(true)
        private set
    var disclaimerAccepted by mutableStateOf(false)
        private set

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        themeMode = p.getString("theme", "dark") ?: "dark"
        fontSize = p.getInt("font", 12)
        wrapLines = p.getBoolean("wrap", true)
        disclaimerAccepted = p.getBoolean("disclaimer", false)
    }

    fun setThemeMode(context: Context, mode: String) {
        themeMode = mode
        prefs(context).edit().putString("theme", mode).apply()
    }

    fun setFontSize(context: Context, size: Int) {
        fontSize = size.coerceIn(9, 20)
        prefs(context).edit().putInt("font", fontSize).apply()
    }

    fun setWrapLines(context: Context, wrap: Boolean) {
        wrapLines = wrap
        prefs(context).edit().putBoolean("wrap", wrap).apply()
    }

    fun acceptDisclaimer(context: Context) {
        disclaimerAccepted = true
        prefs(context).edit().putBoolean("disclaimer", true).apply()
    }

    fun resetDisclaimer(context: Context) {
        disclaimerAccepted = false
        prefs(context).edit().putBoolean("disclaimer", false).apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
    }
}
