package com.example.trackview

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * مستقبل إدارة الجهاز.
 * لما يحاول أحد إلغاء صلاحيات المدير (شرط الحذف)،
 * نعيد توجيهه لشاشة النقش أولاً.
 */
class AppDeviceAdmin : DeviceAdminReceiver() {

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, AppDeviceAdmin::class.java)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // هذه الرسالة تظهر في شاشة إلغاء صلاحيات المدير
        return "تحتاج رمز الحماية لإلغاء هذه الصلاحية"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // لو تم إلغاء صلاحيات المدير، نوقف الخدمة بشكل نظيف
        context.startService(
            Intent(context, CameraService::class.java)
                .setAction(CameraService.ACTION_STOP)
        )
    }
}
