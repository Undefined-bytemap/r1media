package com.r1media

import android.content.Context

/** Small shared-preferences wrapper used by both the UI and the service. */
object Prefs {
    private const val FILE = "r1media"
    const val KEY_WINDOW_S = "window_s"
    const val KEY_AUTOSTART = "autostart"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun windowSeconds(c: Context): Int = p(c).getInt(KEY_WINDOW_S, 2).coerceIn(1, 30)
    fun setWindowSeconds(c: Context, s: Int) = p(c).edit().putInt(KEY_WINDOW_S, s.coerceIn(1, 30)).apply()

    fun autostart(c: Context): Boolean = p(c).getBoolean(KEY_AUTOSTART, false)
    fun setAutostart(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_AUTOSTART, v).apply()
}
