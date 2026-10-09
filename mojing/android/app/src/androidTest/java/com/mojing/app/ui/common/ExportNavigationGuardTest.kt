package com.mojing.app.ui.common

import androidx.compose.material3.Button
import com.mojing.app.ui.theme.MoJingTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.util.ContentDocumentWriter
import java.io.File
import org.junit.Assert.assertTrue

class ExportNavigationGuardTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun navigationRunsImmediatelyWhenNotExporting() {
        var navigations = 0
        rule.setContent {
            MoJingTheme(themeMode = InstrumentationRegistry.getArguments().getString("captureTheme") ?: "light") {
                val navigate = rememberExportNavigationGuard(exporting = { false }, onStopExport = {})
                Button(onClick = { navigate { navigations++ } }, modifier = androidx.compose.ui.Modifier.testTag("navigate")) {
                    Text("导航")
                }
            }
        }
        rule.onNodeWithTag("navigate").performClick()
        rule.runOnIdle { assertEquals(1, navigations) }
    }

    @Test
    fun continueExportDismissesDialogWithoutNavigating() {
        val exporting = mutableStateOf(true)
        var navigations = 0
        rule.setContent {
            MoJingTheme(themeMode = InstrumentationRegistry.getArguments().getString("captureTheme") ?: "light") {
                val navigate = rememberExportNavigationGuard(exporting = { exporting.value }, onStopExport = {})
                Button(onClick = { navigate { navigations++ } }, modifier = androidx.compose.ui.Modifier.testTag("navigate")) {
                    Text("导航")
                }
            }
        }
        rule.onNodeWithTag("navigate").performClick()
        rule.onNodeWithText("继续导出").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(0, navigations)
            assertEquals(true, exporting.value)
        }
        rule.onNodeWithText("正在导出").assertDoesNotExist()
        rule.runOnIdle { exporting.value = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, navigations) }
    }

    @Test
    fun stopAndLeaveRunsTheFirstPendingNavigationOnce() {
        val exporting = mutableStateOf(true)
        var navigations = 0
        val events = mutableListOf<String>()
        val releaseStop = CompletableDeferred<Unit>()
        val stopVersion = mutableStateOf(1)
        rule.setContent {
            MoJingTheme(themeMode = InstrumentationRegistry.getArguments().getString("captureTheme") ?: "light") {
                val version = stopVersion.value
                val navigate = rememberExportNavigationGuard(
                    exporting = { exporting.value },
                    onStopExport = { events += "stop$version"; releaseStop.await(); exporting.value = false },
                )
                Button(onClick = { navigate { navigations++ } }, modifier = androidx.compose.ui.Modifier.testTag("navigate")) {
                    Text("导航")
                }
            }
        }
        rule.onNodeWithTag("navigate").performClick()
        rule.runOnIdle { stopVersion.value = 2 }
        rule.onNodeWithText("停止并离开").performClick()
        rule.runOnIdle {
            assertEquals(listOf("stop2"), events)
            assertEquals(0, navigations)
        }
        rule.onNodeWithText("继续导出").assertIsNotEnabled()
        releaseStop.complete(Unit)
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("stop2"), events)
            assertEquals(1, navigations)
        }
        rule.onNodeWithText("正在导出").assertDoesNotExist()
    }

    @Test
    fun completedOrFailedExportStaysToShowItsResult() {
        val exporting = mutableStateOf(true)
        var navigations = 0
        var replacement = false
        rule.setContent {
            MoJingTheme(themeMode = InstrumentationRegistry.getArguments().getString("captureTheme") ?: "light") {
                val navigate = rememberExportNavigationGuard(
                    exporting = { exporting.value },
                    onStopExport = {},
                )
                Button(onClick = {
                    navigate { navigations++ }
                    navigate { replacement = true }
                }, modifier = androidx.compose.ui.Modifier.testTag("navigate")) {
                    Text("导航")
                }
            }
        }
        rule.onNodeWithTag("navigate").performClick()
        rule.runOnIdle { exporting.value = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, navigations) }
        rule.runOnIdle { assertEquals(false, replacement) }
        rule.onNodeWithText("正在导出").assertDoesNotExist()
    }

    @Test
    fun stoppedExportAllowsTheActionsImmediateFollowUpNavigation() {
        val exporting = mutableStateOf(true)
        var navigations = 0
        rule.setContent {
            MoJingTheme(themeMode = InstrumentationRegistry.getArguments().getString("captureTheme") ?: "light") {
                val navigate = rememberExportNavigationGuard(
                    exporting = { exporting.value },
                    onStopExport = { exporting.value = false },
                )
                Button(onClick = { navigate { navigate { navigations++ } } }) { Text("创建后打开") }
            }
        }
        rule.onNodeWithText("创建后打开").performClick()
        rule.onNodeWithText("停止并离开").performClick()
        rule.runOnIdle { assertEquals(1, navigations) }
        rule.onNodeWithText("正在导出").assertDoesNotExist()
    }

    @Test
    fun confirmedExitWaitsForRealDocumentWriterCancellation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("export-guard-", ".txt", context.cacheDir)
        val started = CompletableDeferred<Unit>()
        val exporting = mutableStateOf(true)
        var closed = false
        var navigations = 0
        lateinit var job: Job
        try {
            rule.setContent {
                MoJingTheme(themeMode = InstrumentationRegistry.getArguments().getString("captureTheme") ?: "light") {
                    val scope = rememberCoroutineScope()
                    DisposableEffect(Unit) {
                        job = scope.launch {
                            try {
                                ContentDocumentWriter.writeStream(context, android.net.Uri.fromFile(file)) { output ->
                                    output.write("partial".toByteArray())
                                    started.complete(Unit)
                                    awaitCancellation()
                                }
                            } finally {
                                closed = true
                                exporting.value = false
                            }
                        }
                        onDispose { job.cancel() }
                    }
                    val navigate = rememberExportNavigationGuard(
                        exporting = { exporting.value }, onStopExport = { job.cancelAndJoin() },
                    )
                    Button(onClick = { navigate {
                        assertTrue(closed)
                        assertTrue(job.isCompleted)
                        assertEquals("partial", file.readText())
                        navigations++
                    } }) { Text("离开导出页面") }
                }
            }
            rule.waitUntil(5_000) { started.isCompleted }
            rule.onNodeWithText("离开导出页面").performClick()
            rule.onNodeWithText("离开会中断导出，目标文件可能不完整。").assertIsDisplayed()
            InstrumentationRegistry.getArguments().getString("captureRun")?.let { run ->
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.waitForIdle(250, 3_000)
                val directory = File(context.getExternalFilesDir(null), run).apply { mkdirs() }
                val bitmap = automation.takeScreenshot()
                File(directory, "export-navigation-confirm.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            rule.onNodeWithText("停止并离开").performClick()
            rule.waitUntil(5_000) { navigations == 1 }
        } finally {
            kotlinx.coroutines.runBlocking { job.cancelAndJoin() }
            file.delete()
        }
    }
}
