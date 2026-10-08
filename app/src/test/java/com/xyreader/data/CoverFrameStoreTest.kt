package com.xyreader.data

import android.app.Application
import com.xyreader.stats.StreakTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoverFrameStoreTest {

    @Test
    fun defaultIsNoneAndWritesSurviveStoreRecreation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // 未写入过时不使用边框
        assertNull(CoverFrameStore(context).selected.first())

        // 写入后换新实例读回，验证已落盘；五档都能往返
        for (tier in StreakTier.entries) {
            CoverFrameStore(context).set(tier)
            assertEquals(tier, CoverFrameStore(context).selected.first())
        }

        // 选「无」清除
        CoverFrameStore(context).set(null)
        assertNull(CoverFrameStore(context).selected.first())
    }

    @Test
    fun unknownOrMissingStoredValuesFallBackToNull() {
        assertNull(decodeCoverFrame(null))
        assertNull(decodeCoverFrame("NONE"))
        assertNull(decodeCoverFrame(""))
        // 以后版本删掉档位、或写入脏数据时不崩溃
        assertNull(decodeCoverFrame("DIAMOND"))
        assertNull(decodeCoverFrame("gold"))
        assertEquals(StreakTier.GOLD, decodeCoverFrame("GOLD"))
        assertEquals(StreakTier.RAINBOW, decodeCoverFrame("RAINBOW"))
    }
}
