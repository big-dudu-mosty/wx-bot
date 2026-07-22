package com.flowbot.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
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
        if (event.packageName?.toString() == WECHAT_PACKAGE) {
            HealthStore.recordWeChatEvent(this)
            if (CaptureStore.isWaiting(this)) ScreenCaptureService.captureNextWeChatFrame(this)
        }
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
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
