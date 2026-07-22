package com.flowbot.agent

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.stardust.view.accessibility.AccessibilityServiceUtils
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var captureResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 16f
        }
        val openSettings = Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            setOnClickListener { AccessibilityServiceUtils.goToAccessibilitySetting(this@MainActivity) }
        }
        captureResult = TextView(this).apply { textSize = 14f }
        val startCapture = Button(this).apply {
            text = getString(R.string.start_capture)
            setOnClickListener {
                startActivityForResult(
                    getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
                    SCREEN_CAPTURE_REQUEST,
                )
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(openSettings)
            addView(startCapture)
            addView(captureResult)
        })
    }

    override fun onResume() {
        super.onResume()
        val enabled = AccessibilityServiceUtils.isAccessibilityServiceEnabled(this, BotAccessibilityService::class.java)
        val health = HealthStore.snapshot(this)
        status.text = getString(
            R.string.health_status,
            if (enabled) getString(R.string.status_enabled) else getString(R.string.status_disabled),
            health.accessibilityConnectedAt.formatTime(),
            health.healthServiceStartedAt.formatTime(),
            health.lastWeChatEventAt.formatTime(),
        )
        captureResult.text = CaptureStore.result(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
            captureResult.text = getString(R.string.capture_open_wechat)
        }
    }

    private fun Long.formatTime(): String = if (this == 0L) {
        getString(R.string.not_available)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(this))
    }

    private companion object {
        const val SCREEN_CAPTURE_REQUEST = 1
    }
}
