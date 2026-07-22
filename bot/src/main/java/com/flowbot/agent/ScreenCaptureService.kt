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
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capturePending = false
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private val captureTimeout = Runnable {
        if (capturePending) {
            capturePending = false
            reader?.setOnImageAvailableListener(null, null)
            CaptureStore.saveError(this, getString(R.string.capture_timeout))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE) {
            captureNext()
            return START_NOT_STICKY
        }
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification())
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), resultData)
            ?: run {
                CaptureStore.saveError(this, getString(R.string.capture_failed))
                stopSelf()
                return START_NOT_STICKY
            }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (CaptureStore.isWaiting(this@ScreenCaptureService)) {
                    CaptureStore.saveError(this@ScreenCaptureService, getString(R.string.capture_failed))
                }
                finish()
            }
        }, handler)
        createDisplay()
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
        if (capturePending) return
        val imageReader = reader ?: run {
            CaptureStore.saveError(this, getString(R.string.capture_failed))
            stopSelf()
            return
        }
        imageReader.acquireLatestImage()?.let {
            recognize(it)
            return
        }
        capturePending = true
        imageReader.setOnImageAvailableListener({ availableReader ->
            val image = availableReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            availableReader.setOnImageAvailableListener(null, null)
            capturePending = false
            handler.removeCallbacks(captureTimeout)
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
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            .process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { CaptureStore.saveText(this, it.text) }
            .addOnFailureListener { CaptureStore.saveError(this, getString(R.string.capture_failed)) }
            .addOnCompleteListener {
                frame.recycle()
                stopSelf()
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
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_CAPTURE = "com.flowbot.agent.CAPTURE"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1002
        private const val CAPTURE_TIMEOUT_MS = 3_000L

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            CaptureStore.arm(context)
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun captureNextWeChatFrame(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
