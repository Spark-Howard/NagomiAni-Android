package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.maccms.SourceHealth
import com.sparkhoward.nagomiani.core.store.AppStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceHealthTest {

    private fun entry(ok: Boolean, ms: Long) = AppStore.SiteHealthEntry(ok = ok, latencyMs = ms, testedAt = 0)

    // MARK: - fastestHost（站点最快标记）

    @Test
    fun fastestHostSkipsUnreachableAndPicksMinimum() {
        val sites = mapOf(
            "a.com" to entry(true, 300),
            "b.com" to entry(true, 120),
            "c.com" to entry(false, 0),
        )
        assertEquals("b.com", SourceHealth.fastestHost(sites))
    }

    @Test
    fun fastestHostReturnsNullWhenAllUnreachableOrEmpty() {
        assertNull(SourceHealth.fastestHost(mapOf("a.com" to entry(false, 0))))
        assertNull(SourceHealth.fastestHost(emptyMap()))
    }

    // MARK: - fastestRouteIndex（线路 ⚡ 标记）

    @Test
    fun fastestRoutePicksMinimumIgnoringTimeouts() {
        assertEquals(2, SourceHealth.fastestRouteIndex(listOf(500L, null, 120L, 340L)))
    }

    @Test
    fun fastestRouteTieGoesToEarlierIndex() {
        assertEquals(0, SourceHealth.fastestRouteIndex(listOf(120L, 120L)))
    }

    @Test
    fun fastestRouteNullWhenAllTimeoutOrEmpty() {
        assertNull(SourceHealth.fastestRouteIndex(listOf(null, null)))
        assertNull(SourceHealth.fastestRouteIndex(emptyList()))
    }

    // MARK: - nextRouteOnFailure（断流自动换线）

    @Test
    fun nextRouteCyclesForward() {
        assertEquals(1, SourceHealth.nextRouteOnFailure(0, 3, 0))
        assertEquals(2, SourceHealth.nextRouteOnFailure(1, 3, 0))
        assertEquals(0, SourceHealth.nextRouteOnFailure(2, 3, 0))
    }

    @Test
    fun nextRouteGivesUpAfterExhaustingOtherRoutes() {
        // 3 条线路最多自动兜底 2 次（换遍其余线路），第 3 次放弃
        assertNull(SourceHealth.nextRouteOnFailure(0, 3, 2))
        assertNull(SourceHealth.nextRouteOnFailure(1, 3, 2))
    }

    @Test
    fun nextRouteNullForSingleRoute() {
        assertNull(SourceHealth.nextRouteOnFailure(0, 1, 0))
    }

    // MARK: - probeURL（探测地址必须补全 API 路径）

    @Test
    fun probeURLOnBareDomainGetsFullAPIPath() {
        // 内置站是裸域名：直接拼参数会打到根路径（量子/极速 403、暴风 500 → 误判不可达）
        assertEquals(
            "https://cj.lziapi.com/api.php/provide/vod/?ac=list&pg=1",
            SourceHealth.probeURL("https://cj.lziapi.com"),
        )
    }

    @Test
    fun probeURLOnNormalizedBaseStaysIntact() {
        // 用户自加站（已带 /api.php/provide/vod/ 路径）不重复补全；
        // 末尾斜杠被 trimEnd 去掉后拼参数，与 MacCMSApi.fetch 对同一存储串的行为一致
        assertEquals(
            "https://example.com/api.php/provide/vod?ac=list&pg=1",
            SourceHealth.probeURL("https://example.com/api.php/provide/vod/"),
        )
    }

    // MARK: - formatLatency（延迟展示）

    @Test
    fun latencyFormatsAsMsUnderOneSecond() {
        assertEquals("213ms", SourceHealth.formatLatency(213))
        assertEquals("0ms", SourceHealth.formatLatency(0))
    }

    @Test
    fun latencyFormatsAsSecondsOverOneSecond() {
        assertEquals("1.0s", SourceHealth.formatLatency(1000))
        assertEquals("1.2s", SourceHealth.formatLatency(1200))
        assertTrue(SourceHealth.formatLatency(62_500).endsWith("s"))
    }
}
