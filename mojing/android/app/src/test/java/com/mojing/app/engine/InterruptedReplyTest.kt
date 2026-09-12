package com.mojing.app.engine

import com.mojing.app.domain.engine.InterruptedReply
import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptedReplyTest {
    @Test fun keepsProseWithoutIncompleteMediaInstructions() {
        assertEquals("门开了。", InterruptedReply.normalize("<NARRATION>门开了。</NARRATION><GEN_IMAGE>昏暗的门"))
        assertEquals("门开了。", InterruptedReply.normalize("门开了。<GEN_SPE"))
    }
    @Test fun keepsReceivedDialogueWithoutDanglingWrappers() {
        assertEquals("快走", InterruptedReply.normalize("<SPEECH name=\"林汐\">快走"))
        assertEquals("山路", InterruptedReply.normalize("<NARRATION>山路</NARR"))
    }
    @Test fun doesNotOfferIncompleteChoice() {
        assertEquals("走到门前。", InterruptedReply.normalize("走到门前。<CHOICES><OPTION>开门"))
    }
    @Test fun keepsOrdinaryTextAndCompleteChoice() {
        assertEquals("1 < 2，继续。", InterruptedReply.normalize("1 < 2，继续。"))
        assertEquals("<OPTION>开门</OPTION>", InterruptedReply.normalize("<OPTION>开门</OPTION>"))
    }
}
