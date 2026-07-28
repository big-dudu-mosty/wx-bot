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
import com.flowbot.agent.db.MessageDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capturePending = false
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private val dbExecutor = Executors.newSingleThreadExecutor()

    private val captureTimeout = Runnable {
        if (capturePending) {
            capturePending = false
            reader?.setOnImageAvailableListener(null, null)
            Log.w(TAG, "Capture timeout")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> {
                captureNext()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                CollectionState.stopCollection(this)
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
                stopSelf()
                return START_NOT_STICKY
            }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                CollectionState.stopCollection(this@ScreenCaptureService)
                finish()
            }
        }, handler)
        createDisplay()
        CollectionState.startCollection(this)
        Log.i(TAG, "ScreenCaptureService started, collection active")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
        val imageReader = reader ?: return

        // Try to acquire an existing image first
        imageReader.acquireLatestImage()?.let {
            CollectionState.recordCapture(this)
            recognize(it)
            return
        }

        // Wait for next frame
        capturePending = true
        imageReader.setOnImageAvailableListener({ availableReader ->
            val image = availableReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            availableReader.setOnImageAvailableListener(null, null)
            capturePending = false
            handler.removeCallbacks(captureTimeout)
            CollectionState.recordCapture(this)
            recognize(image)
        }, handler)
        handler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
    }

    private fun recognize(captured: android.media.Image) {
        val width = captured.width
        val height = captured.height
        val plane = captured.planes[0]
        val bitmap = Bitmap.createBitmap(
            width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        captured.close()
        val frame = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        bitmap.recycle()

        val screenWidth = width
        val screenHeight = height

        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            .process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { text -> processOcrResult(text, screenWidth, screenHeight) }
            .addOnFailureListener { e -> Log.e(TAG, "OCR failed", e) }
            .addOnCompleteListener { frame.recycle() }
    }

    private fun processOcrResult(text: Text, screenWidth: Int, screenHeight: Int) {
        if (text.text.isBlank()) return

        val parser = MessageParser(screenWidth, screenHeight)
        val parsed = parser.parse(text)
        if (parsed.isEmpty()) return

        val entities = parser.toEntities(parsed, text.text)
        val db = MessageDatabase.getInstance(this)

        dbExecutor.execute {
            val inserted = db.messageDao().insertAll(entities)
            val newCount = inserted.count { it != -1L }
            if (newCount > 0) {
                Log.i(TAG, "Inserted $newCount new messages (${inserted.size - newCount} duplicates skipped)")
            }
        }
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
        handler.removeCallbacks(captureTimeout)
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
        private const val CAPTURE_TIMEOUT_MS = 3_000L

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun captureNextFrame(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
