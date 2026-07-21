package com.flowbot.agent

import android.content.Context

object HealthStore {
    private const val PREFERENCES = "bot_health"
    private const val ACCESSIBILITY_CONNECTED_AT = "accessibility_connected_at"
    private const val HEALTH_SERVICE_STARTED_AT = "health_service_started_at"
    private const val LAST_WECHAT_EVENT_AT = "last_wechat_event_at"

    fun recordAccessibilityConnected(context: Context) = write(context, ACCESSIBILITY_CONNECTED_AT)

    fun recordHealthServiceStarted(context: Context) = write(context, HEALTH_SERVICE_STARTED_AT)

    fun recordWeChatEvent(context: Context) = write(context, LAST_WECHAT_EVENT_AT)

    fun snapshot(context: Context): Snapshot {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return Snapshot(
            accessibilityConnectedAt = preferences.getLong(ACCESSIBILITY_CONNECTED_AT, 0),
            healthServiceStartedAt = preferences.getLong(HEALTH_SERVICE_STARTED_AT, 0),
            lastWeChatEventAt = preferences.getLong(LAST_WECHAT_EVENT_AT, 0),
        )
    }

    private fun write(context: Context, key: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(key, System.currentTimeMillis())
            .apply()
    }

    data class Snapshot(
        val accessibilityConnectedAt: Long,
        val healthServiceStartedAt: Long,
        val lastWeChatEventAt: Long,
    )
}
