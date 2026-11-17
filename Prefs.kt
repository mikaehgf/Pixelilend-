package com.pixelisland

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private val defaults = mapOf(
        "cw" to 96, "ch" to 30, "ew" to 350, "eh" to 84,
        "y" to 7, "x" to 0, "r" to 28, "fly" to 1
    )

    fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("island", Context.MODE_PRIVATE)

    fun get(c: Context, key: String): Int = sp(c).getInt(key, defaults.getValue(key))
    fun set(c: Context, key: String, v: Int) = sp(c).edit().putInt(key, v).apply()

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()
}
