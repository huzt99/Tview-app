package com.example.trackview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        // عدد نقاط العينة في الصورة (كل ما زادت زادت الدقة)
        private const val GRID_W = 32
        private const val GRID_H = 24

        // الفرق في السطوع اللي يعتبر تغيير في النقطة (من 0 إلى 255)
        private const val PIXEL_THRESHOLD = 25

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        setContentView(root)
        showIdleStatus()

        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                .also { it.setAnalyzer(analysisExecutor, MotionAnalyzer()) }

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

    private inner class MotionAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastCompareTime < COMPARE_INTERVAL_MS) return
                lastCompareTime = now

                // القناة الأولى في الصورة هي السطوع (Y)
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
            } finally {
                image.close()
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(resetStatus)
        toneGenerator?.release()
        analysisExecutor.shutdown()
    }
}
