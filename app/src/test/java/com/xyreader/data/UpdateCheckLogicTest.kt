package com.xyreader.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 内置更新器的纯逻辑单测：版本比较 [isNewer] 与 GitHub 最新发布 JSON 解析 [parseLatestRelease]。
 * 用 Robolectric 提供真实 org.json 实现（纯 JVM 单测里 android.jar 的 org.json 是 stub）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateCheckLogicTest {

    // ---- isNewer：分段数字比较 ----

    @Test fun remoteOlderIsNotNewer() {
        assertFalse(isNewer("0.4.11", "0.5.0"))
    }

    @Test fun equalVersionsAreNotNewerIncludingVPrefix() {
        assertFalse(isNewer("0.5.0", "v0.5.0"))
        assertFalse(isNewer("v0.5.0", "0.5.0"))
        assertFalse(isNewer("V0.5.0", "0.5.0")) // 大写 V 同样剥离
        assertFalse(isNewer("0.5.0", "0.5.0"))
    }

    @Test fun multiDigitSegmentComparesNumerically() {
        // 字符串比较会误判 "0.5.9" > "0.5.10"，数字比较才是对的
        assertTrue(isNewer("0.5.10", "0.5.9"))
        assertFalse(isNewer("0.5.9", "0.5.10"))
    }

    @Test fun missingSegmentsCountAsZero() {
        assertFalse(isNewer("1.0", "1.0.0"))
        assertTrue(isNewer("1.0.1", "1.0"))
    }

    @Test fun illegalVersionDoesNotClaimAnUpdate() {
        assertFalse(isNewer("abc", "abc")) // 相等 → 无更新
        assertFalse(isNewer("abc", "abd")) // 非法值无法安全排序
        assertFalse(isNewer("v abc", "abc")) // 前缀仍剥离
        assertFalse(isNewer("-1.2", "0.5.0"))
        assertFalse(isNewer("+1.2", "0.5.0"))
    }

    // ---- parseLatestRelease：字段解析 ----

    private val normalRelease = """
        {
          "tag_name": "v0.5.1",
          "name": "XY-READER 0.5.1",
          "prerelease": false,
          "draft": false,
          "body": "## 更新内容\n- 修复 A\n- **优化** B",
          "html_url": "https://github.com/TerryYu12/xy-reader/releases/tag/v0.5.1",
          "assets": [
            {
              "name": "XY-READER-0.5.1-debug.apk",
              "browser_download_url": "https://example.com/XY-READER-debug.apk",
              "size": 111
            },
            {
              "name": "XY-READER-0.5.1.apk",
              "browser_download_url": "https://example.com/XY-READER-0.5.1.apk",
              "size": 2222
            }
          ]
        }
    """.trimIndent()

    @Test fun parsesFieldsAndPicksNonDebugApkAsset() {
        val info = parseLatestRelease(normalRelease)
        assertEquals("0.5.1", info?.version)
        assertEquals("v0.5.1", info?.tag)
        assertEquals("https://github.com/TerryYu12/xy-reader/releases/tag/v0.5.1", info?.htmlUrl)
        assertEquals("https://example.com/XY-READER-0.5.1.apk", info?.assetUrl)
        assertEquals(2222L, info?.assetSize)
        assertTrue(info?.notes.orEmpty().contains("更新内容"))
    }

    @Test fun skewsDebugOnlyAssetsToNull() {
        val json = normalRelease.replace(
            """"XY-READER-0.5.1.apk"""",
            """"XY-READER-0.5.1-debug-only.apk"""",
        )
        val info = parseLatestRelease(json)
        assertNull(info?.assetUrl)
        assertEquals(0L, info?.assetSize)
    }

    @Test fun noAssetsGivesNullUrl() {
        val json = normalRelease.substringBefore("\"assets\"")
            .trimEnd()
            .removeSuffix(",") + "\n}"
        val info = parseLatestRelease(json)
        assertEquals("0.5.1", info?.version)
        assertNull(info?.assetUrl)
    }

    @Test fun prereleaseIsSkipped() {
        val json = normalRelease.replace("\"prerelease\": false", "\"prerelease\": true")
        assertNull(parseLatestRelease(json))
    }

    @Test fun draftIsSkipped() {
        val json = normalRelease.replace("\"draft\": false", "\"draft\": true")
        assertNull(parseLatestRelease(json))
    }

    @Test fun malformedJsonIsAParseFailure() {
        assertThrows(Exception::class.java) { parseLatestRelease("not json at all") }
    }

    @Test fun missingRequiredReleaseMetadataIsAParseFailure() {
        assertThrows(Exception::class.java) {
            parseLatestRelease("""{"prerelease":false,"draft":false,"tag_name":"v0.5.1"}""")
        }
    }

    @Test fun invalidTagIsAParseFailure() {
        val json = normalRelease.replace("v0.5.1", "v0.5.x")
        assertThrows(Exception::class.java) { parseLatestRelease(json) }
    }

    @Test fun inconsistentOrEmptyDownloadSizesAreRejected() {
        assertThrows(java.io.IOException::class.java) {
            validateDownloadSizes(expectedAssetSize = 2222, contentLength = 2222, downloadedBytes = 2221)
        }
        assertThrows(java.io.IOException::class.java) {
            validateDownloadSizes(expectedAssetSize = 0, contentLength = -1, downloadedBytes = 0)
        }
    }

    @Test fun unknownDownloadSizeCanUseMeasuredContentLength() {
        validateDownloadSizes(expectedAssetSize = 0, contentLength = 11, downloadedBytes = 11)
    }
}
