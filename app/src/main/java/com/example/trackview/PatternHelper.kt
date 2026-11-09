package com.example.trackview

import android.content.Context
import java.security.MessageDigest

object PatternHelper {

    private const val PREFS = "tview"
    private const val KEY = "pattern_hash"

    /** هل فيه نقش محفوظ؟ */
    fun hasPattern(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains(KEY)
    }

    /** يحفظ النقش كـ hash */
    fun savePattern(context: Context, pattern: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, hash(pattern))
            .apply()
    }

    /** يتحقق إذا النقش صحيح */
    fun verify(context: Context, pattern: String): Boolean {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return false
        return saved == hash(pattern)
    }

    /** يمسح النقش المحفوظ (للاختبار) */
    fun clearPattern(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    private fun hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
