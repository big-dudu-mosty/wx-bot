package com.flowbot.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BotAccessibilityService : com.stardust.view.accessibility.AccessibilityService() {
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

        // Only trigger capture when collecting and throttle allows
        if (!CollectionState.isCollecting(this)) return
        if (!ScreenCaptureService.isSessionActive()) return
        if (!CollectionState.canCapture(this)) return

        // Event classes are often child controls rather than the visible WeChat screen.
        // ScreenCaptureService only persists OCR that identifies a group header.
        Log.d(TAG, "Triggering capture from accessibility event: ${event.className}")
        ScreenCaptureService.captureNextFrame(this)
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
