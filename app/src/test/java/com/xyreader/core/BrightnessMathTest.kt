package com.xyreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 超低亮度映射：普通区与历史行为一致，负值区压最低窗口亮度并叠加黑色遮罩 */
class BrightnessMathTest {

    private val eps = 1e-6f

    @Test fun normalRangeKeepsLegacyWindowBrightnessAndHasNoDim() {
        for (v in listOf(0.01f, 0.1f, 0.35f, 0.5f, 1f)) {
            assertEquals(v, BrightnessMath.windowBrightness(v), eps)
            assertEquals(0f, BrightnessMath.dimAlpha(v), eps)
            assertFalse(BrightnessMath.isBelowSystemMin(v))
        }
    }

    @Test fun followSystemHasNoDim() {
        assertEquals(0f, BrightnessMath.dimAlpha(null), eps)
        assertFalse(BrightnessMath.isBelowSystemMin(null))
    }

    @Test fun negativeValuesPinWindowToFloorAndAddDim() {
        for (v in listOf(-0.01f, -0.2f, BrightnessMath.MIN)) {
            assertEquals(BrightnessMath.WINDOW_MIN, BrightnessMath.windowBrightness(v), eps)
            assertTrue(BrightnessMath.isBelowSystemMin(v))
        }
    }

    @Test fun dimGrowsMonotonicallyTowardMinAndCapsAtMax() {
        assertEquals(0f, BrightnessMath.dimAlpha(0f), eps)
        assertEquals(BrightnessMath.MAX_DIM_ALPHA / 2, BrightnessMath.dimAlpha(BrightnessMath.MIN / 2), eps)
        assertEquals(BrightnessMath.MAX_DIM_ALPHA, BrightnessMath.dimAlpha(BrightnessMath.MIN), eps)
        // 超出下限的输入（理论上不会出现）不会让遮罩超过上限
        assertEquals(BrightnessMath.MAX_DIM_ALPHA, BrightnessMath.dimAlpha(-3f), eps)
        var last = 0f
        var v = 0f
        while (v >= BrightnessMath.MIN) {
            val alpha = BrightnessMath.dimAlpha(v)
            assertTrue("v=$v alpha=$alpha last=$last", alpha >= last)
            last = alpha
            v -= 0.02f
        }
    }

    @Test fun windowBrightnessIsContinuousAcrossZero() {
        // 0 附近两侧窗口亮度都是下限，不会在切换处跳变
        assertEquals(BrightnessMath.WINDOW_MIN, BrightnessMath.windowBrightness(0f), eps)
        assertEquals(BrightnessMath.WINDOW_MIN, BrightnessMath.windowBrightness(-0.001f), eps)
        assertEquals(BrightnessMath.WINDOW_MIN, BrightnessMath.windowBrightness(0.01f), eps)
        assertEquals(0f, BrightnessMath.dimAlpha(0f), eps)
        assertEquals(0f, BrightnessMath.dimAlpha(0.004f), eps)
    }

    @Test fun illegalInputsAreClamped() {
        assertEquals(BrightnessMath.MAX, BrightnessMath.clamp(5f), eps)
        assertEquals(BrightnessMath.MIN, BrightnessMath.clamp(-5f), eps)
        assertEquals(BrightnessMath.WINDOW_MIN, BrightnessMath.clamp(Float.NaN), eps)
        assertEquals(BrightnessMath.WINDOW_MIN, BrightnessMath.windowBrightness(Float.NaN), eps)
        assertEquals(0f, BrightnessMath.dimAlpha(Float.NaN), eps)
        assertEquals(1f, BrightnessMath.windowBrightness(9f), eps)
    }

    @Test fun percentLabelShowsWindowPercentOrDepthBelowSystemMin() {
        assertEquals("100%", BrightnessMath.percentLabel(1f))
        assertEquals("35%", BrightnessMath.percentLabel(0.35f))
        assertEquals("1%", BrightnessMath.percentLabel(0.01f))
        assertEquals("1%", BrightnessMath.percentLabel(0f))
        // 满深度 = 比系统最低亮度再暗 85%
        assertEquals("-85%", BrightnessMath.percentLabel(BrightnessMath.MIN))
    }

    @Test fun hintGuidesUserToTheDimZoneAndExplainsDepth() {
        assertEquals(null, BrightnessMath.hint(0.5f))
        assertEquals(null, BrightnessMath.hint(1f))
        assertEquals("继续向左拖，可比系统最低亮度更暗", BrightnessMath.hint(0.1f))
        assertEquals("继续向左拖，可比系统最低亮度更暗", BrightnessMath.hint(0f))
        assertEquals("比系统最低亮度再暗 85%", BrightnessMath.hint(BrightnessMath.MIN))
    }
}
