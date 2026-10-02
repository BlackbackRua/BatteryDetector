package com.blackback.batterydetector

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blackback.batterydetector.network.LanSyncEngine
import com.blackback.batterydetector.shizuku.HyperOsFocusNotification
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the HyperOS focus-notification path.
 *
 * Must run as the app's own process: the `canShowFocus` provider rejects any
 * caller that does not own the queried package, so shell/ADB cannot read the
 * gate for us.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest
 * then inspect `adb logcat -s HyperOsFocusVerify`.
 */
@RunWith(AndroidJUnit4::class)
class HyperOsFocusInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reportsDeviceCapabilityAndFocusGate() {
        val support = HyperOsFocusNotification.detectSupport(context)

        Log.i(TAG, "=== capability ===")
        Log.i(TAG, "isXiaomi=${support.isXiaomi}")
        Log.i(TAG, "protocolVersion=${support.protocolVersion}")
        Log.i(TAG, "hasFocusPermission=${support.hasFocusPermission}")
        Log.i(TAG, "capability=${support.capability}")
        Log.i(TAG, "summary=${support.summary}")

        // The device is expected to be HyperOS 3 with island support.
        assertTrue("expected a Xiaomi device", support.isXiaomi)
    }

    @Test
    fun buildsPayloadAndPostsNotificationCarryingFocusExtras() {
        val notificationId = 10099
        val content = HyperOsFocusNotification.Content(
            title = "BatteryDetector 上岛验证",
            body = "设备验证：超级岛载荷测试",
            deviceName = "test-device",
            batteryLevel = 42,
            isCharging = false,
            isTest = true,
            iconRes = R.drawable.ic_home
        )

        // Build only, so the payload can be inspected without going through the
        // XMSF race (which needs Shizuku running).
        val notification = HyperOsFocusNotification.build(context, LanSyncEngine.CHANNEL_ID, content)

        val payload = notification.extras.getString("miui.focus.param")
        Log.i(TAG, "=== payload ===")
        Log.i(TAG, "miui.focus.param=$payload")
        assertNotNull("focus payload must be attached", payload)
        assertTrue("payload must be rooted at param_v2", payload!!.contains("\"param_v2\""))
        assertTrue("payload must carry the island block", payload.contains("param_island"))
        assertTrue("payload must carry the big island", payload.contains("bigIslandArea"))

        val pics = notification.extras.getBundle("miui.focus.pics")
        Log.i(TAG, "miui.focus.pics keys=${pics?.keySet()}")
        assertNotNull("pics bundle must be attached", pics)
        assertTrue(
            "pics bundle must hold the island icon",
            pics!!.keySet().any { it == HyperOsFocusNotification.islandIconBundleKey() }
        )

        // Actually post it so `dumpsys notification` can prove the system accepted it.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
        Log.i(TAG, "posted id=$notificationId channel=${LanSyncEngine.CHANNEL_ID}")
    }

    @Test
    fun readsFocusGateDirectly() {
        val extras = Bundle().apply { putString("package", context.packageName) }
        val result = try {
            context.contentResolver.call(
                android.net.Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus",
                null,
                extras
            )
        } catch (e: Exception) {
            Log.w(TAG, "canShowFocus call failed: ${e.javaClass.name}: ${e.message}")
            null
        }
        Log.i(TAG, "canShowFocus raw bundle=$result")
        Log.i(TAG, "canShowFocus value=${result?.getBoolean("canShowFocus", false)}")
        Log.i(TAG, "bundle keys=${result?.keySet()}")
    }

    private companion object {
        const val TAG = "HyperOsFocusVerify"
    }
}
