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
import com.flowbot.agent.db.CandidateKind
import com.stardust.view.accessibility.AccessibilityServiceUtils
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var collectionStatus: TextView
    private lateinit var collectionCounts: TextView
    private lateinit var diagnostics: TextView
    private lateinit var recentCandidates: TextView
    private lateinit var reportDraft: TextView
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
        collectionCounts = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        diagnostics = TextView(this).apply {
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }

        val openSettings = Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            setOnClickListener { AccessibilityServiceUtils.goToAccessibilitySetting(this@MainActivity) }
        }

        toggleButton = Button(this).apply {
            setOnClickListener { onToggleCollection() }
        }

        val generateReport = Button(this).apply {
            text = getString(R.string.generate_daily_report)
            setOnClickListener { generateDailyReport() }
        }

        recentCandidates = TextView(this).apply {
            textSize = 12f
            setPadding(0, 16, 0, 0)
        }
        reportDraft = TextView(this).apply {
            textSize = 12f
            setPadding(0, 24, 0, 24)
        }

        val scrollView = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(recentCandidates)
                addView(reportDraft)
            })
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
            addView(collectionCounts)
            addView(diagnostics)
            addView(openSettings)
            addView(toggleButton)
            addView(generateReport)
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

        val collecting = CollectionState.isCollecting(this) && ScreenCaptureService.isSessionActive()
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

        dbExecutor.execute {
            val db = MessageDatabase.getInstance(this)
            val dao = db.collectionDao()
            val observationCount = dao.observationCount()
            val candidateCount = dao.candidateCount()
            val after = System.currentTimeMillis() - RECENT_WINDOW_MS
            val digestCount = dao.candidateDigestCount(after)
            val recent = dao.recentCandidateDigests(after, CandidateKind.TEXT, 10)
            val event = dao.latestEvent()

            runOnUiThread {
                collectionCounts.text = getString(R.string.collection_counts, observationCount, candidateCount, digestCount)
                diagnostics.text = getString(
                    R.string.collection_diagnostics,
                    CollectionState.lastTraceId(this),
                    CollectionState.lastErrorCode(this),
                    event?.let { "${it.stage}/${it.outcome}${it.errorCode?.let { code -> "/$code" } ?: ""}" } ?: "-",
                )
                if (recent.isEmpty()) {
                    recentCandidates.text = getString(R.string.no_candidates_yet)
                } else {
                    recentCandidates.text = recent.joinToString("\n\n") { candidate ->
                        val repeats = if (candidate.seenCount > 1) "（同屏出现 ${candidate.seenCount} 次）" else ""
                        "[${candidate.groupNameHint}] ${candidate.sender}: ${candidate.content}$repeats"
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

    private fun generateDailyReport() {
        reportDraft.text = getString(R.string.generating_daily_report)
        dbExecutor.execute {
            val after = System.currentTimeMillis() - RECENT_WINDOW_MS
            val candidates = MessageDatabase.getInstance(this).collectionDao()
                .recentCandidateDigests(after, CandidateKind.TEXT, REPORT_SOURCE_LIMIT)
            val draft = DailyReportGenerator.generate(candidates)
            runOnUiThread { reportDraft.text = draft }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
            refreshUI()
        }
    }

    override fun onDestroy() {
        dbExecutor.shutdown()
        super.onDestroy()
    }

    private fun Long.formatTime(): String = if (this == 0L) {
        getString(R.string.not_available)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(this))
    }

    private companion object {
        const val SCREEN_CAPTURE_REQUEST = 1
        const val RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L
        const val REPORT_SOURCE_LIMIT = Int.MAX_VALUE
    }
}
