package com.example.trackview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var remoteStatusText: TextView
    private lateinit var addressText: TextView
    private lateinit var toggleButton: Button
    private lateinit var remoteLink: String

    private val uiHandler = Handler(Looper.getMainLooper())
    private val resetStatus = Runnable { showIdleStatus() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraPermission()) {
                startMonitoring()
            } else {
                Toast.makeText(this, "لازم تسمح باستخدام الكاميرا", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remoteLink = "${CameraService.VIEWER_BASE}?room=${loadOrCreateRoom(this)}"

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
        }
        val remoteParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        remoteParams.topMargin = 280
        root.addView(remoteStatusText, remoteParams)

        // الأزرار والروابط (أسفل الشاشة)
        toggleButton = Button(this).apply {
            setOnClickListener {
                if (CameraService.monitoring) {
                    stopMonitoring()
                } else {
                    ensurePermissionsAndStart()
                }
            }
        }

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
            addView(toggleButton)
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
        refreshUi()

        // أول فتح للتطبيق: نبدأ المراقبة تلقائيا
        if (savedInstanceState == null && !CameraService.monitoring) {
            ensurePermissionsAndStart()
        }
    }

    override fun onStart() {
        super.onStart()
        CameraService.onMotion = { runOnUiThread { showMotion() } }
        CameraService.onRemoteStatus = { message ->
            runOnUiThread { remoteStatusText.text = message }
        }
        CameraService.onStateChanged = { runOnUiThread { refreshUi() } }
        CameraService.setPreviewView(previewView)
        refreshUi()
    }

    override fun onStop() {
        super.onStop()
        CameraService.onMotion = null
        CameraService.onRemoteStatus = null
        CameraService.onStateChanged = null
        // الشاشة ما عادت ظاهرة: نوقف عرض الصورة، والكاميرا تكمل شغلها بالخدمة
        CameraService.setPreviewView(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(resetStatus)
    }

    // ------------------------------------------------------------------

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isEmpty()) {
            startMonitoring()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startMonitoring() {
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
    }

    private fun stopMonitoring() {
        startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_STOP))
    }

    private fun refreshUi() {
        if (CameraService.monitoring) {
            toggleButton.text = "إيقاف المراقبة"
            showIdleStatus()
            remoteStatusText.text = CameraService.lastRemoteStatus
            val lan = if (CameraService.lanLink.isNotEmpty()) {
                "\n\nعلى نفس الشبكة:\n${CameraService.lanLink}"
            } else {
                ""
            }
            addressText.text = "رابط المشاهدة من أي مكان:\n$remoteLink$lan"
        } else {
            toggleButton.text = "ابدأ المراقبة"
            statusText.text = "المراقبة متوقفة"
            statusText.setBackgroundColor(Color.parseColor("#99616161"))
            remoteStatusText.text = "—"
            addressText.text = "رابط المشاهدة من أي مكان:\n$remoteLink"
        }
    }

    private fun showMotion() {
        if (!CameraService.monitoring) return
        statusText.text = "⚠️ تم رصد حركة"
        statusText.setBackgroundColor(Color.parseColor("#CCD32F2F"))
        uiHandler.removeCallbacks(resetStatus)
        uiHandler.postDelayed(resetStatus, 2000)
    }

    private fun showIdleStatus() {
        statusText.text = "المراقبة شغالة"
        statusText.setBackgroundColor(Color.parseColor("#99388E3C"))
    }
}
