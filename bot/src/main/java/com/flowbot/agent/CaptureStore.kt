package com.flowbot.agent

import android.content.Context

object CaptureStore {
    private const val PREFERENCES = "capture"
    private const val TEXT = "text"
    private const val ERROR = "error"
    private const val WAITING = "waiting"

    fun arm(context: Context) = preferences(context).edit().clear().putBoolean(WAITING, true).apply()

    fun isWaiting(context: Context) = preferences(context).getBoolean(WAITING, false)

    fun saveText(context: Context, text: String) = save(context, TEXT, text)

    fun saveError(context: Context, error: String) = save(context, ERROR, error)

    fun result(context: Context): String {
        val preferences = preferences(context)
        return preferences.getString(ERROR, null)
            ?: preferences.getString(TEXT, null)
            ?: context.getString(R.string.capture_waiting)
    }

    private fun save(context: Context, key: String, value: String) {
        preferences(context)
            .edit()
            .clear()
            .putBoolean(WAITING, false)
            .putString(key, value)
            .apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
