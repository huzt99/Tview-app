package com.example.trackview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * يشتغل تلقائياً لما يُعاد تشغيل الجهاز،
 * ويعيد تشغيل خدمة المراقبة بدون أي تدخل من المستخدم.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CameraService::class.java)
            )
        }
    }
}
