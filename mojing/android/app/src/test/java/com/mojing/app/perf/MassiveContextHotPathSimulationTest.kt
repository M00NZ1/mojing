package com.mojing.app.perf

import com.mojing.app.domain.engine.TokenCounter
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * 检查单条超长正文和 UI 有界窗口的 token 估算成本（非真机 UI 帧率）。
 *
 * 打开和刷新聊天只处理最近 80 条，双向浏览最多保留 200 条；模型热路径只读取最近
 * 400 条上下文。原始历史仍完整保存在 Room，并通过 keyset 查询按需读取。
 */
class MassiveContextHotPathSimulationTest {

    @Test
    fun tokenEstimate_loadedWindow_isBounded() {
        val msgCount = 200
        val charsPerMessage = 2_000
        val body = "中".repeat(charsPerMessage)
        val contents = List(msgCount) { body }

        val ms = measureTimeMillis {
            var sum = 0
            for (c in contents) {
                sum += TokenCounter.estimateScaledPrefix(c)
            }
            assertTrue(sum > 0)
        }
        // 开发机参考：通常应远小于数秒；若 CI 极慢可调大阈值或减小 msgCount
        assertTrue("token 估算耗时 ${ms}ms 过长", ms < 120_000)
    }

    @Test
    fun tokenEstimate_singleHugeString_extrapolateRisk() {
        // 单条 ~40 万字符：模拟「单会话巨型气泡」量级的一角（仍远小于 500 万 token 等价文本）
        val chars = 400_000
        val blob = "α".repeat(chars) // 非 ASCII，走「×2」分支，偏悲观
        val ms = measureTimeMillis {
            val est = TokenCounter.estimate(blob)
            assertTrue(est > 0)
        }
        assertTrue("单条 ${chars} 字符估算耗时 ${ms}ms", ms < 60_000)
    }
}
