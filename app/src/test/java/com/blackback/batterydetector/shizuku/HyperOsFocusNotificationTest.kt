package com.blackback.batterydetector.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These assertions pin the parts of the HyperOS focus-notification contract that
 * are easy to get wrong and are cheap to check without a device:
 * the extras keys and the icon key naming.
 *
 * They deliberately do not fabricate a payload: the JSON shape is built with
 * [org.json.JSONObject], which is unavailable in a plain JVM test, so the full
 * document is verified on a device instead of being asserted from a stub.
 */
class HyperOsFocusNotificationTest {

    @Test
    fun `island icon key carries the documented prefix exactly once`() {
        val key = HyperOsFocusNotification.islandIconBundleKey()

        assertEquals("miui.focus.pic_island_icon", key)
        // The payload builder used to be handed an already-prefixed key, which
        // produced "miui.focus.pic_miui.focus.pic_island_icon". Guard that.
        assertEquals(1, Regex("miui\\.focus\\.pic_").findAll(key).count())
    }

    @Test
    fun `island icon short key is the unprefixed name`() {
        val short = HyperOsFocusNotification.islandIconKey()

        assertEquals("island_icon", short)
        assertTrue(
            "bundle key must be built from the short key",
            HyperOsFocusNotification.islandIconBundleKey().endsWith(short)
        )
    }

    @Test
    fun `test notifications are labelled as tests regardless of charging state`() {
        assertEquals("测试通知", HyperOsFocusNotification.stateLabel(isCharging = true, isTest = true))
        assertEquals("测试通知", HyperOsFocusNotification.stateLabel(isCharging = false, isTest = true))
    }

    @Test
    fun `charging state is reported when not a test`() {
        assertEquals("充电中", HyperOsFocusNotification.stateLabel(isCharging = true, isTest = false))
        assertEquals("电量预警", HyperOsFocusNotification.stateLabel(isCharging = false, isTest = false))
    }
}
