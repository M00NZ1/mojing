package com.mojing.app.story

import com.mojing.app.domain.story.StoryStreamingPreviewParser
import org.junit.Assert.assertEquals
import org.junit.Test

class StoryStreamingPreviewParserTest {
    @Test fun splitJsonAndEscapedUnicodeAreDecodedIncrementally() {
        val parser = StoryStreamingPreviewParser()
        "{\"chapters\":[{\"title\":\"一\",\"content\":\"雾\\n港 ".chunked(2).forEach(parser::append)
        "\\u4E2D\\u56FD\"}],\"next_choices\":[\"继续\",\"离开\"]}".chunked(3).forEach(parser::append)
        assertEquals("雾\n港 中国", parser.previewText())
    }

    @Test fun incompleteJsonKeepsOnlyReceivedContent() {
        val parser = StoryStreamingPreviewParser()
        parser.append("{\"chapters\":[{\"content\":\"尚未完成")
        assertEquals("尚未完成", parser.previewText())
        assertEquals("尚未完成", parser.previewText())
    }

    @Test fun everySingleCharacterSplitStillFindsContent() {
        val json = "{\"chapters\":[{\"content\":\"一段\\n中文\\u4E2D\"}]}"
        (0..json.length).forEach { split ->
            val parser = StoryStreamingPreviewParser()
            json.take(split).forEach { parser.append(it.toString()) }
            json.drop(split).forEach { parser.append(it.toString()) }
            assertEquals("一段\n中文中", parser.previewText())
        }
    }
}
