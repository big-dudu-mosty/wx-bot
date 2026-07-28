package com.flowbot.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BotAccessibilityService : com.stardust.view.accessibility.AccessibilityService() {

    // Track whether WeChat is currently the foreground window
    private var wechatInForeground = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf(WECHAT_PACKAGE)
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 250
        }
        HealthStore.recordAccessibilityConnected(this)
        startHealthService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        super.onAccessibilityEvent(event)
        if (event.packageName?.toString() != WECHAT_PACKAGE) return

        HealthStore.recordWeChatEvent(this)

        // Track WeChat foreground state via window state changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            wechatInForeground = className.startsWith("com.tencent.mm")
        }

        // Only trigger capture when collecting and throttle allows
        if (!CollectionState.isCollecting(this)) return
        if (!CollectionState.canCapture(this)) return
        if (!wechatInForeground) return

        // Only capture when in a chat UI (not contacts/discover tabs)
        val className = event.className?.toString() ?: return
        if (!isChatUI(className)) return

        Log.d(TAG, "Triggering capture from accessibility event: $className")
        ScreenCaptureService.captureNextFrame(this)
    }

    private fun isChatUI(className: String): Boolean {
        // Only capture in actual chat conversation views, not the chat list
        return className.contains("ChattingUI") ||
            className.contains("chatting", ignoreCase = true)
    }

    private fun startHealthService() {
        val intent = Intent(this, BotHealthService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private companion object {
        const val TAG = "BotAccessibility"
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
