package com.mojing.app.ui

import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.MainActivity
import com.mojing.app.data.WorldEditDraft
import com.mojing.app.data.WorldEditDraftStore
import com.mojing.app.data.local.entity.EncyclopediaEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in complete App check, scoped to a newly inserted UUID world and its draft keys. */
class WorldDraftEqualityAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun waitText(text: String) = rule.waitUntil(8_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
    private fun hideKeyboard() {
        fun shown(): Boolean = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
        ).bufferedReader().use { it.readText() }.contains("mInputShown=true")
        if (shown()) back()
        rule.waitUntil(5_000) { !shown() }
    }
    private fun openSettings() {
        rule.onAllNodesWithContentDescription("更多操作").onFirst().performClick()
        rule.onNodeWithText("世界设置").performClick()
        waitText("世界名称")
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        repeat(2) {
            val frame = CountDownLatch(1)
            rule.runOnUiThread {
                val decor = rule.activity.window.decorView
                decor.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                decor.invalidate()
            }
            check(frame.await(3, TimeUnit.SECONDS))
        }
        val dir = File(context.getExternalFilesDir(null), "world-draft-equality").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            check(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun equalDraftIsEditableAndDifferentDraftStillRestoresAndSaves() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("worldDraftCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        val db = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val dao = db.encyclopediaDao()
        val store = WorldEditDraftStore(context)
        val name = "草稿核对-${UUID.randomUUID()}"
        val id = io { dao.upsert(EncyclopediaEntity(name = name, description = "本批合成简介", worldPrompt = "合成世界设定", antiCheatPrompt = "合成规则")) }
        try {
            val world = io { dao.getById(id)!! }
            io { store.save(id, WorldEditDraft(world.name, world.description, world.worldPrompt, world.gameplayMode, world.antiCheatPrompt)) }
            back() // Dismiss the production splash overlay, as in the existing App captures.
            waitText("创作")
            rule.onAllNodesWithText("创作").filter(hasClickAction()).onLast().performClick()
            waitText("世界")
            rule.onNodeWithText("世界").performClick()
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(name)
            hideKeyboard()
            waitText(name)
            openSettings()
            rule.onNodeWithText("恢复草稿").assertDoesNotExist()
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().assertIsEnabled().assertTextContains(name)
            assertNull(io { store.load(id) })
            capture("equal-editable")
            val changed = "$name · 修改"
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(changed)
            hideKeyboard()
            back()
            waitText("保留草稿并离开")
            rule.onNodeWithText("保留草稿并离开").performClick()
            openSettings()
            waitText("恢复草稿")
            assertEquals(name, io { dao.getById(id)!!.name })
            rule.onNodeWithText(name, useUnmergedTree = true).assertIsNotEnabled()
            capture("different-recovery")
            rule.onNodeWithText("恢复草稿").performClick()
            rule.onNodeWithText("保存世界设置").performClick()
            waitText("已保存")
            assertEquals(changed, io { dao.getById(id)!!.name })
            assertNull(io { store.load(id) })
            back()
            // The world's name changed and no longer matches the exact search: refresh the filter.
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(changed)
            hideKeyboard()
            openSettings()
            rule.onNodeWithText("恢复草稿").assertDoesNotExist()
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().assertIsEnabled().assertTextContains(changed)
            capture("saved-reentered")
            back()
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement("")
            hideKeyboard()
        } finally {
            io {
                check(dao.getById(id)?.name?.startsWith(name) == true)
                store.clear(id)
                dao.delete(id)
                assertNull(dao.getById(id))
                assertNull(store.load(id))
            }
        }
    }
}
