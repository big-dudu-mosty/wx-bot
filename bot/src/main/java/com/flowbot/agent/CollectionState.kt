package com.flowbot.agent

import android.content.Context

/**
 * Manages the collection state — whether the bot is actively capturing messages.
 * Also provides throttling via lastCaptureTime.
 */
object CollectionState {
    private const val PREFERENCES = "collection_state"
    private const val KEY_COLLECTING = "is_collecting"
    private const val KEY_LAST_CAPTURE = "last_capture_time"
    private const val KEY_LAST_TRACE = "last_trace_id"
    private const val KEY_LAST_ERROR = "last_error_code"
    private const val MIN_CAPTURE_INTERVAL_MS = 5_000L

    fun isCollecting(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COLLECTING, false)

    fun startCollection(context: Context) {
        prefs(context).edit().putBoolean(KEY_COLLECTING, true).apply()
    }

    fun stopCollection(context: Context) {
        prefs(context).edit().putBoolean(KEY_COLLECTING, false).apply()
    }

    fun recordCapture(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CAPTURE, System.currentTimeMillis()).apply()
    }

    fun beginTrace(context: Context, traceId: String) {
        prefs(context).edit().putString(KEY_LAST_TRACE, traceId).remove(KEY_LAST_ERROR).apply()
    }

    fun recordError(context: Context, traceId: String, errorCode: String) {
        prefs(context).edit().putString(KEY_LAST_TRACE, traceId).putString(KEY_LAST_ERROR, errorCode).apply()
    }

    fun canCapture(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CAPTURE, 0L)
        return System.currentTimeMillis() - last >= MIN_CAPTURE_INTERVAL_MS
    }

    fun lastCaptureTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CAPTURE, 0L)

    fun lastTraceId(context: Context): String = prefs(context).getString(KEY_LAST_TRACE, null) ?: "-"

    fun lastErrorCode(context: Context): String = prefs(context).getString(KEY_LAST_ERROR, null) ?: "-"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
