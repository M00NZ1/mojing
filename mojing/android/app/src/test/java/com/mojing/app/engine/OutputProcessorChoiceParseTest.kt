package com.mojing.app.engine

import com.mojing.app.domain.engine.OutputProcessor
import com.mojing.app.domain.engine.StructuredParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputProcessorChoiceParseTest {
    @Test
    fun normalizeOptionsShouldConvertNumberedListIntoOptionTags() {
        val raw = """
            <NARRATION>场景。</NARRATION>
            <SPEECH>你要怎么做？</SPEECH>
            可选行动：
            1. 去酒馆打听消息
            2) 直接去城门
            - 先回家休息
        """.trimIndent()
        val normalized = OutputProcessor.normalizeOptions(raw)
        assertFalse(normalized.contains("1. 去酒馆打听消息"))
        assertEquals(
            listOf("去酒馆打听消息", "直接去城门", "先回家休息"),
            OutputProcessor.extractChoices(normalized),
        )
    }

    @Test
    fun normalizeOptionsRecoversNumberedChoicesInsideChoicesWrapper() {
        val raw = """
            夜色笼罩了车站。
            <CHOICES>
            1. 前往站台
            2. 留在候车室
            3. 找工作人员询问
            </CHOICES>
        """.trimIndent()

        val normalized = OutputProcessor.normalizeOptions(raw)

        assertEquals(
            listOf("前往站台", "留在候车室", "找工作人员询问"),
            OutputProcessor.extractChoices(normalized),
        )
        assertFalse(normalized.contains("1. 前往站台"))
    }

    @Test
    fun normalizeOptionsRecoversMarkdownHeadingAndLabelledChoices() {
        val raw = """
            她停下来等待你的回答。

            **后续选项：**
            **选项一：继续追问**
            **选项二：暂时离开**
        """.trimIndent()

        assertEquals(
            listOf("继续追问", "暂时离开"),
            OutputProcessor.extractChoices(raw),
        )
    }

    @Test
    fun normalizeOptionsRecoversExplicitChoicesBeforeClosingProse() {
        val raw = """
            她把手放在门把上，回头看你。

            ### 可选行动（任选其一）：
            > 1. 推门进入
            > （二）先观察四周

            请选择你接下来要做的事。
        """.trimIndent()

        val normalized = OutputProcessor.normalizeOptions(raw)

        assertEquals(
            listOf("推门进入", "先观察四周"),
            OutputProcessor.extractChoices(normalized),
        )
        assertFalse(normalized.contains("> 1. 推门进入"))
        assertTrue(normalized.contains("请选择你接下来要做的事。"))
    }

    @Test
    fun normalizeOptionsAcceptsSingleChoiceInsideExplicitWrapper() {
        val raw = "正文。\n<CHOICES>\n1. 继续\n</CHOICES>"

        assertEquals(listOf("继续"), OutputProcessor.extractChoices(raw))
    }

    @Test
    fun normalizeOptionsDoesNotConsumeStoryListFollowedByMoreProse() {
        val raw = """
            桌上放着：
            1. 一封信
            2. 一把钥匙

            她把两样东西都收进抽屉。
        """.trimIndent()

        assertEquals(raw, OutputProcessor.normalizeOptions(raw))
        assertTrue(OutputProcessor.extractChoices(raw).isEmpty())
    }

    @Test
    fun fourShortMarkdownMarkersRemainRawAndNormalizationIsIdempotent() {
        for (marker in listOf("**", "***", "__", "___")) {
            assertEquals(marker, OutputProcessor.normalizeOptions(marker))
            assertEquals(marker, OutputProcessor.normalizeOptions(OutputProcessor.normalizeOptions(marker)))
            assertTrue(OutputProcessor.extractChoices(marker).isEmpty())
            assertEquals(marker, StructuredParser.parse(marker).plainText)
        }
    }

    @Test
    fun shortMarkersAlongsideCompleteXmlKeepTheRawTextAndParsedBody() {
        for (marker in listOf("**", "***", "__", "___")) {
            val raw = "<NARRATION>旁白。</NARRATION>\n$marker"
            assertEquals(raw, OutputProcessor.normalizeOptions(raw))
            val reply = StructuredParser.parse(raw)
            assertEquals(listOf("旁白。"), reply.narrations)
            assertEquals(marker, reply.plainText)
            assertTrue(reply.choices.isEmpty())
            assertEquals(raw, OutputProcessor.normalizeOptions(OutputProcessor.normalizeOptions(raw)))
        }
    }

    @Test
    fun shortMarkersInsideTruncatedXmlRemainRawAndDoNotInventChoices() {
        for (marker in listOf("**", "***", "__", "___")) {
            for (tag in listOf("NARRATION", "CHOICES")) {
                val raw = "<$tag>\n$marker"
                assertEquals(raw, OutputProcessor.normalizeOptions(raw))
                assertEquals(raw, OutputProcessor.normalizeOptions(OutputProcessor.normalizeOptions(raw)))
                assertTrue(OutputProcessor.extractChoices(raw).isEmpty())
                assertFalse(StructuredParser.isStructured(raw))
            }
        }
    }

    @Test
    fun shortMarkersInsideCompleteChoiceBlocksKeepExistingLiteralChoiceRules() {
        // '*' remains the existing bullet prefix; '_' remains literal in explicit blocks.
        for ((marker, choice) in listOf("**" to "*", "***" to "**", "__" to "__", "___" to "___")) {
            val raw = "<SPEECH>对白。</SPEECH><CHOICES>$marker</CHOICES>"
            val normalized = OutputProcessor.normalizeOptions(raw)
            assertEquals("<SPEECH>对白。</SPEECH><CHOICES><OPTION>$choice</OPTION></CHOICES>", normalized)
            assertEquals(listOf(choice), OutputProcessor.extractChoices(raw))
            assertEquals(listOf("对白。"), StructuredParser.parse(raw).speeches.map { it.text })
            assertEquals(normalized, OutputProcessor.normalizeOptions(normalized))
            assertEquals("<SPEECH>对白。</SPEECH><CHOICES>$marker</CHOICES>", raw)
        }
    }

    @Test
    fun completeWrappersAndChoicesNormalizeWhileUnclosedMarkersStayRaw() {
        for (wrapper in listOf("**", "__")) {
            val raw = "<SPEECH>正文。</SPEECH>\n${wrapper}后续选项：$wrapper\n${wrapper}选项一：继续$wrapper\n${wrapper}选项二：离开$wrapper"
            val normalized = OutputProcessor.normalizeOptions(raw)
            assertEquals(listOf("继续", "离开"), OutputProcessor.extractChoices(raw))
            assertEquals(normalized, OutputProcessor.normalizeOptions(normalized))
            assertEquals(listOf("正文。"), StructuredParser.parse(raw).speeches.map { it.text })
        }
        for (raw in listOf("**正文", "正文**", "__正文", "正文__", "***", "___")) {
            assertEquals(raw, OutputProcessor.normalizeOptions(raw))
            assertEquals(raw, OutputProcessor.normalizeOptions(OutputProcessor.normalizeOptions(raw)))
        }
    }

}
