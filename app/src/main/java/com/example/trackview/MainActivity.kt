package com.example.trackview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
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

        // ---- البث من أي مكان ----
        // رابط قاعدة بيانات Firebase حقتك
        private const val DB_URL = "https://tview-12deb-default-rtdb.europe-west1.firebasedatabase.app"

        // رابط صفحة المشاهدة على GitHub Pages (غيّره إذا كان اسم حسابك أو المستودع مختلف)
        private const val VIEWER_BASE = "https://huzt99.github.io/Tview-app/"
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var remoteStatusText: TextView
    private lateinit var addressText: TextView
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var server: MjpegServer
    private lateinit var remote: RemoteStreamer

    private val pin = (1000..9999).random().toString()

    private val uiHandler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null

    private var previousFrame: IntArray? = null
    private var lastCompareTime = 0L
    private var lastAlertTime = 0L
    private var lastStreamTime = 0L

    private val resetStatus = Runnable { showIdleStatus() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "لازم تسمح باستخدام الكاميرا", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        analysisExecutor = Executors.newSingleThreadExecutor()
        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            null
        }

        val root = FrameLayout(this)

        previewView = PreviewView(this)
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // حالة الحركة (أعلى الشاشة)
        statusText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(48, 24, 48, 24)
        }
        val statusParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        statusParams.topMargin = 120
        root.addView(statusText, statusParams)

        // حالة البث عن بعد (تحت حالة الحركة)
        remoteStatusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(32, 12, 32, 12)
            text = "جاري تجهيز البث من أي مكان…"
        }
        val remoteParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        remoteParams.topMargin = 280
        root.addView(remoteStatusText, remoteParams)

        // الروابط وزر المشاركة (أسفل الشاشة)
        val room = loadOrCreateRoom()
        val remoteLink = "$VIEWER_BASE?room=$room"

        val shareButton = Button(this).apply {
            text = "مشاركة رابط المشاهدة"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, remoteLink)
                }
                startActivity(Intent.createChooser(send, "مشاركة الرابط"))
            }
        }

        addressText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(32, 20, 32, 20)
            setTextIsSelectable(true)
        }

        val bottomBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(shareButton)
            addView(addressText)
        }
        val bottomParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        bottomParams.bottomMargin = 100
        root.addView(bottomBox, bottomParams)

        setContentView(root)
        showIdleStatus()

        // البث على نفس الشبكة
        server = MjpegServer(PORT, pin)
        server.start()

        // البث من أي مكان
        remote = RemoteStreamer(this, DB_URL, room)
        remote.onStatus = { message ->
            runOnUiThread { remoteStatusText.text = message }
        }
        remote.start()

        val ip = getLocalIp()
        val lan = if (ip != null) "\n\nعلى نفس الشبكة:\nhttp://$ip:$PORT/?pin=$pin" else ""
        addressText.text = "رابط المشاهدة من أي مكان:\n$remoteLink$lan"

        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** كود الغرفة: طويل وعشوائي، ويتحفظ عشان الرابط يبقى نفسه كل مرة */
    private fun loadOrCreateRoom(): String {
        val prefs = getSharedPreferences("tview", MODE_PRIVATE)
        val saved = prefs.getString("room", null)
        if (saved != null) return saved

        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        val created = (1..24).map { chars[random.nextInt(chars.length)] }.joinToString("")
        prefs.edit().putString("room", created).apply()
        return created
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, FrameAnalyzer()) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            try {
                val now = System.currentTimeMillis()

                // 1) كشف الحركة
                if (now - lastCompareTime >= COMPARE_INTERVAL_MS) {
                    lastCompareTime = now
                    detectMotion(image, now)
                }

                // 2) البث على نفس الشبكة (فقط إذا فيه أحد متصل)
                if (server.clients.get() > 0 && now - lastStreamTime >= STREAM_INTERVAL_MS) {
                    lastStreamTime = now
                    server.update(imageToJpeg(image))
                }

                // 3) البث من أي مكان (فقط إذا فيه مشاهد متصل)
                remote.pushFrame(image)
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

    private fun onMotionDetected(now: Long) {
        runOnUiThread {
            statusText.text = "⚠️ تم رصد حركة"
            statusText.setBackgroundColor(Color.parseColor("#CCD32F2F"))
            uiHandler.removeCallbacks(resetStatus)
            uiHandler.postDelayed(resetStatus, 2000)
        }
        if (now - lastAlertTime > ALERT_COOLDOWN_MS) {
            lastAlertTime = now
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        }
    }

    private fun showIdleStatus() {
        statusText.text = "المراقبة شغالة"
        statusText.setBackgroundColor(Color.parseColor("#99388E3C"))
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

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(resetStatus)
        remote.stop()
        server.stop()
        toneGenerator?.release()
        analysisExecutor.shutdown()
    }
}
