package com.flowbot.agent

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.flowbot.agent.db.MessageDatabase
import com.stardust.view.accessibility.AccessibilityServiceUtils
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var collectionStatus: TextView
    private lateinit var messageCount: TextView
    private lateinit var recentMessages: TextView
    private lateinit var toggleButton: Button
    private val dbExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        collectionStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        messageCount = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val openSettings = Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            setOnClickListener { AccessibilityServiceUtils.goToAccessibilitySetting(this@MainActivity) }
        }

        toggleButton = Button(this).apply {
            setOnClickListener { onToggleCollection() }
        }

        recentMessages = TextView(this).apply {
            textSize = 12f
            setPadding(0, 16, 0, 0)
        }

        val scrollView = ScrollView(this).apply {
            addView(recentMessages)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(collectionStatus)
            addView(messageCount)
            addView(openSettings)
            addView(toggleButton)
            addView(scrollView)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun refreshUI() {
        val enabled = AccessibilityServiceUtils.isAccessibilityServiceEnabled(this, BotAccessibilityService::class.java)
        val health = HealthStore.snapshot(this)
        status.text = getString(
            R.string.health_status,
            if (enabled) getString(R.string.status_enabled) else getString(R.string.status_disabled),
            health.accessibilityConnectedAt.formatTime(),
            health.healthServiceStartedAt.formatTime(),
            health.lastWeChatEventAt.formatTime(),
        )

        val collecting = CollectionState.isCollecting(this)
        collectionStatus.text = if (collecting) {
            getString(R.string.collection_active)
        } else {
            getString(R.string.collection_inactive)
        }

        toggleButton.text = if (collecting) {
            getString(R.string.stop_collection)
        } else {
            getString(R.string.start_collection)
        }

        // Load message count and recent messages from DB
        dbExecutor.execute {
            val db = MessageDatabase.getInstance(this)
            val count = db.messageDao().count()
            val recent = db.messageDao().getRecent(10)

            runOnUiThread {
                messageCount.text = getString(R.string.message_count, count)
                if (recent.isEmpty()) {
                    recentMessages.text = getString(R.string.no_messages_yet)
                } else {
                    recentMessages.text = recent.joinToString("\n\n") { msg ->
                        "[${msg.groupName}] ${msg.sender}: ${msg.content}"
                    }
                }
            }
        }
    }

    private fun onToggleCollection() {
        if (CollectionState.isCollecting(this)) {
            // Stop collection
            ScreenCaptureService.stop(this)
            CollectionState.stopCollection(this)
            refreshUI()
        } else {
            // Request MediaProjection permission
            startActivityForResult(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
                SCREEN_CAPTURE_REQUEST,
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
            refreshUI()
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
