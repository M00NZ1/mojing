package com.mojing.app.acceptance

import android.net.Uri
import android.speech.RecognizerIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.media.NativeSpeechRecognizer
import com.mojing.app.util.ChatAttachmentFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-level checks for media boundaries that cannot be proven by the JVM tests.
 *
 * Routing precedence and TTS token replacement are intentionally covered by the
 * deterministic JVM tests (ChatViewModelTest and TtsPlayerTest); this class does
 * not depend on a configured provider, microphone service, or audio hardware.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRouteMediaAcceptanceTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun speechIntentCarriesStableChineseFreeFormContract() {
        val intent = NativeSpeechRecognizer.createIntent()

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        assertEquals("zh-CN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals("请说话...", intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT))
    }

    @Test
    fun fileUriIsCopiedThroughRealContentResolverIntoOwnedSessionMedia() = runBlocking {
        val sessionId = 71_001_000_000L + (System.nanoTime() and 0xFFFFL)
        val source = File(context.cacheDir, "acceptance-source-${System.nanoTime()}.bin")
        val payload = "media-boundary".toByteArray()
        val attachmentDir = File(context.filesDir, "attachments/$sessionId")
        val directoryExisted = attachmentDir.exists()
        source.writeBytes(payload)
        var output: File? = null
        try {
            val path = ChatAttachmentFiles.copyUriToSessionFile(
                context = context,
                uri = Uri.fromFile(source),
                sessionId = sessionId,
            )
            output = File(path)
            assertTrue(output.isFile)
            assertTrue(payload.contentEquals(output.readBytes()))
            assertTrue(output.canonicalPath.startsWith(File(context.filesDir, "attachments/$sessionId").canonicalPath))
        } finally {
            source.delete()
            output?.delete()
            if (!directoryExisted) output?.parentFile?.delete()
        }
    }

    @Test
    fun unreadableContentUriFailsWithoutLeavingAttachmentFile() = runBlocking {
        val sessionId = 71_002_000_000L + (System.nanoTime() and 0xFFFFL)
        val directory = File(context.filesDir, "attachments/$sessionId")
        val directoryExisted = directory.exists()
        val before = directory.listFiles()?.toSet().orEmpty()
        try {
            val result = runCatching {
                ChatAttachmentFiles.copyUriToSessionFile(
                    context = context,
                    uri = Uri.parse("content://mojing.acceptance/missing-${System.nanoTime()}"),
                    sessionId = sessionId,
                )
            }

            assertTrue(result.isFailure)
            val after = directory.listFiles()?.toSet().orEmpty()
            assertEquals(before, after)
            assertFalse(after.any { it.name.startsWith("attach_") })
        } finally {
            if (!directoryExisted) directory.delete()
        }
    }
}
