package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.update.UpdateCenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 更新检查的纯函数部分：版本比较 + releases/latest JSON 解析 */
class UpdateCenterTest {

    @Test fun newerVersionDetected() {
        assertTrue(UpdateCenter.isNewer("v0.2.0", "0.1.0"))
        assertTrue(UpdateCenter.isNewer("0.2.0", "v0.1.0")) // 双向容忍 v 前缀
        assertTrue(UpdateCenter.isNewer("v0.1.1", "0.1.0"))
        assertTrue(UpdateCenter.isNewer("v1.0", "0.9.9"))   // 段数不同补 0
        assertTrue(UpdateCenter.isNewer("0.10.0", "0.9.0")) // 数值比较而非字典序
        assertTrue(UpdateCenter.isNewer("0.2.0-beta", "0.1.9")) // 后缀忽略
    }

    @Test fun sameOrOlderVersionIgnored() {
        assertFalse(UpdateCenter.isNewer("v0.1.0", "0.1.0"))
        assertFalse(UpdateCenter.isNewer("v0.1.0", "0.1.1"))
        assertFalse(UpdateCenter.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateCenter.isNewer("v0.1.0-beta", "0.1.0")) // 后缀忽略后同级不算更新
    }

    @Test fun unparsableVersionFailsSafe() {
        assertFalse(UpdateCenter.isNewer("garbage", "0.1.0"))
        assertFalse(UpdateCenter.isNewer("v0.2.0", ""))
        assertFalse(UpdateCenter.isNewer("", "0.1.0"))
    }

    @Test fun parsesLatestReleaseJson() {
        val json = """
            {
              "url": "https://api.github.com/repos/Spark-Howard/NagomiAni-Android/releases/1",
              "tag_name": "v0.2.0",
              "name": "0.2.0",
              "body": "## 修复\r\n- 看完同步",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/Spark-Howard/NagomiAni-Android/releases/tag/v0.2.0",
              "assets": [
                {
                  "name": "NagomiAni-0.2.0.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://github.com/Spark-Howard/NagomiAni-Android/releases/download/v0.2.0/NagomiAni-0.2.0.apk"
                }
              ]
            }
        """.trimIndent()
        val release = UpdateCenter.parseRelease(json)
        assertNotNull(release)
        assertEquals("v0.2.0", release.tagName)
        assertEquals("0.2.0", release.name)
        assertTrue(release.body!!.contains("看完同步"))
        assertEquals(1, release.assets.size)
        assertEquals("NagomiAni-0.2.0.apk", release.apkAsset!!.name)
        assertTrue(release.apkAsset!!.browserDownloadUrl.endsWith(".apk"))
    }

    @Test fun releaseWithoutApkAssetIsUntargetable() {
        val json = """{"tag_name":"v0.2.0","assets":[{"name":"source.zip","browser_download_url":"https://x/s.zip"}]}"""
        val release = UpdateCenter.parseRelease(json)
        assertNotNull(release)
        assertNull(release.apkAsset)
    }

    @Test fun brokenJsonReturnsNull() {
        assertNull(UpdateCenter.parseRelease("not json"))
        assertNull(UpdateCenter.parseRelease(""))
    }
}
