package com.flowbot.agent

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 16f
        }
        val openSettings = Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            setOnClickListener { AccessibilityServiceUtils.goToAccessibilitySetting(this@MainActivity) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(openSettings)
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
    }

    private fun Long.formatTime(): String = if (this == 0L) {
        getString(R.string.not_available)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(this))
    }
}
