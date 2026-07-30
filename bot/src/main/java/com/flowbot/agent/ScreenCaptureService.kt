package com.flowbot.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.flowbot.agent.db.CollectionStore
import com.flowbot.agent.db.MessageDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors
import java.util.UUID

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capturePending = false
    private var pendingTraceId: String? = null
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private val dbExecutor = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> {
                if (sessionActive) captureNext()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                CollectionState.stopCollection(this)
                recordEvent(CollectionState.lastTraceId(this), "CAPTURE", "STOPPED", null, "requested")
                finish()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Initial start with MediaProjection authorization
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification())
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), resultData)
            ?: run {
                Log.e(TAG, "Failed to get MediaProjection")
                CollectionState.stopCollection(this)
                recordFailure(newTraceId(), "CAPTURE_SESSION", "CAPTURE_SESSION_START_FAILED")
                stopSelf()
                return START_NOT_STICKY
            }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                CollectionState.stopCollection(this@ScreenCaptureService)
                recordFailure(newTraceId(), "CAPTURE_SESSION", "CAPTURE_SESSION_STOPPED")
                finish()
                stopSelf()
            }
        }, handler)
        createDisplay()
        CollectionState.startCollection(this)
        sessionActive = true
        Log.i(TAG, "ScreenCaptureService started, collection active")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionActive = false
        finish()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createDisplay() {
        val metrics = resources.displayMetrics
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "flowbot-capture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            null,
        )
    }

    private fun captureNext() {
        if (capturePending || closed) return
        val traceId = newTraceId()
        CollectionState.beginTrace(this, traceId)
        recordEvent(traceId, "CAPTURE", "STARTED", null, "requested")
        val imageReader = reader ?: run {
            recordFailure(traceId, "CAPTURE", "CAPTURE_NOT_READY")
            return
        }
        pendingTraceId = traceId

        // Wait for WeChat to draw, then read the latest frame. Redmi may not emit another
        // ImageReader callback for an otherwise static chat screen.
        capturePending = true
        handler.postDelayed({
            if (!capturePending) return@postDelayed
            capturePending = false
            pendingTraceId = null
            imageReader.acquireLatestImage()?.let { image ->
                CollectionState.recordCapture(this)
                recognize(image, traceId)
            } ?: recordFailure(traceId, "CAPTURE", "CAPTURE_NOT_READY")
        }, FRAME_SETTLE_DELAY_MS)
    }

    private fun recognize(captured: android.media.Image, traceId: String) {
        val frame = try {
            val width = captured.width
            val plane = captured.planes[0]
            val bitmap = Bitmap.createBitmap(
                width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride,
                captured.height,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(plane.buffer)
            Bitmap.createBitmap(bitmap, 0, 0, width, captured.height).also { bitmap.recycle() }
        } catch (error: RuntimeException) {
            recordFailure(traceId, "CAPTURE", "FRAME_CONVERSION_FAILED", error)
            null
        } finally {
            captured.close()
        } ?: return

        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            .process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { text -> processOcrResult(text, frame.width, frame.height, traceId) }
            .addOnFailureListener { error -> recordFailure(traceId, "OCR", "OCR_FAILED", error) }
            .addOnCompleteListener { frame.recycle() }
    }

    private fun processOcrResult(text: Text, screenWidth: Int, screenHeight: Int, traceId: String) {
        if (text.text.isBlank()) {
            recordFailure(traceId, "OCR", "OCR_EMPTY")
            return
        }

        val parser = MessageParser(screenWidth, screenHeight)
        if (!parser.isGroupScreen(text)) {
            recordEvent(traceId, "CLASSIFY", "SKIPPED", "CHAT_TYPE_UNKNOWN", "header without member count")
            return
        }
        val parsed = try {
            parser.parse(text)
        } catch (error: RuntimeException) {
            recordFailure(traceId, "PARSER", "PARSER_FAILED", error)
            return
        }
        dbExecutor.execute {
            try {
                val result = CollectionStore.saveObservation(
                    MessageDatabase.getInstance(this),
                    traceId,
                    text.text,
                    parser.groupNameHint(text),
                    parsed,
                )
                Log.i(TAG, "Stored observation duplicate=${result.duplicate} candidates=${result.candidateCount}")
            } catch (error: RuntimeException) {
                recordFailure(traceId, "DATABASE", "DATABASE_WRITE_FAILED", error)
            }
        }
    }

    private fun recordEvent(traceId: String, stage: String, outcome: String, errorCode: String?, detail: String) {
        dbExecutor.execute {
            runCatching {
                CollectionStore.recordEvent(MessageDatabase.getInstance(this), traceId, stage, outcome, errorCode, detail)
            }
        }
    }

    private fun recordFailure(traceId: String, stage: String, errorCode: String, error: Throwable? = null) {
        CollectionState.recordError(this, traceId, errorCode)
        Log.w(TAG, "$stage failed: $errorCode", error)
        recordEvent(traceId, stage, "FAILED", errorCode, error?.javaClass?.simpleName ?: "")
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.capture_notification_title))
        .setContentText(getString(R.string.capture_notification_text))
        .setOngoing(true)
        .build()

    private fun finish() {
        if (closed) return
        closed = true
        sessionActive = false
        capturePending = false
        reader?.close()
        reader = null
        display?.release()
        display = null
        projection?.stop()
        projection = null
        stopForeground(true)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.capture_notification_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_CAPTURE = "com.flowbot.agent.CAPTURE"
        const val ACTION_STOP = "com.flowbot.agent.STOP"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1002
        private const val FRAME_SETTLE_DELAY_MS = 500L
        @Volatile
        private var sessionActive = false

        private fun newTraceId(): String = UUID.randomUUID().toString()

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun captureNextFrame(context: Context) {
            if (!sessionActive) return
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE)
            context.startService(intent)
        }

        fun isSessionActive(): Boolean = sessionActive

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
