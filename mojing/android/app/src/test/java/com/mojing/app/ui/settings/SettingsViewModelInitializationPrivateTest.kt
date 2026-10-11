package com.mojing.app.ui.settings

import androidx.lifecycle.viewModelScope
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.remote.BackendSystemProbeApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelInitializationPrivateTest {
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val costRecordDao = mockk<CostRecordDao>(relaxed = true)
    private val systemProbeApi = mockk<BackendSystemProbeApi>(relaxed = true)
    private val uiPreferencesRepository = mockk<UiPreferencesRepository>(relaxed = true) {
        every { chatDensity } returns flowOf("comfortable")
    }
    private val worldTemplateDao = mockk<WorldTemplateDao>(relaxed = true)
    private val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun nonDefaultSnapshotPublishesOnceAndBlocksWritersUntilReady() = runTest {
        val readGate = CompletableDeferred<Unit>()
        var cleanupViewModel: SettingsViewModel? = null
        try {
            every { secureStorage.publicApiKey } answers {
                runBlocking {
                    kotlinx.coroutines.withTimeout(5_000) { readGate.await() }
                }
                "stored-key"
            }
            every { secureStorage.publicBaseUrl } returns "https://stored.example"
            every { secureStorage.publicModel } returns "stored-model"
            every { secureStorage.themeMode } returns "light"
            every { secureStorage.uiFontScale } returns 1.25f
            every { secureStorage.userName } returns "存档玩家"
            every { secureStorage.userDescription } returns "存档设定"
            every { secureStorage.userAvatarColor } returns "#123456"
            every { secureStorage.defaultTemperature } returns "1.1"
            every { secureStorage.imageApiKey } returns "image-key"
            every { secureStorage.voiceModel } returns "voice-model"
            val viewModel = newViewModel()
            cleanupViewModel = viewModel
            runCurrent()

            viewModel.updateApiKey("premature-edit")
            viewModel.updateDefaultTemperature("1.2")
            viewModel.updateProfile("不应保存", "", "#ffffff")
            assertTrue(runCatching {
                viewModel.savePlatform(
                    ModelPlatform("blocked", "阻止", "https://blocked.test", "blocked-key", listOf("blocked-model"), selectedModel = "blocked-model"),
                    makeDefault = true,
                )
            }.isFailure)
            verify(exactly = 0) { secureStorage.publicApiKey = any() }
            verify(exactly = 0) { secureStorage.defaultTemperature = any() }
            verify(exactly = 0) { secureStorage.saveUserProfile(any(), any(), any()) }
            verify(exactly = 0) { secureStorage.saveModelPlatform(any(), any()) }
            assertEquals(SettingsInitializationState.Loading, viewModel.initialization.value)

            readGate.complete(Unit)
            assertEquals(SettingsInitializationState.Ready, awaitInitialization(viewModel))
            assertEquals("stored-key", viewModel.apiKey.value)
            assertEquals("https://stored.example", viewModel.baseUrl.value)
            assertEquals("stored-model", viewModel.model.value)
            assertEquals("light", viewModel.themeMode.value)
            assertEquals(1.25f, viewModel.uiFontScale.value)
            assertEquals("存档玩家", viewModel.userName.value)
            assertEquals("存档设定", viewModel.userDescription.value)
            assertEquals("#123456", viewModel.userAvatarColor.value)
            assertEquals("1.1", viewModel.defaultTemperature.value)
            assertEquals("image-key", viewModel.imageApiKey.value)
            assertEquals("voice-model", viewModel.voiceModel.value)
            cancelAndJoin(viewModel)
        } finally {
            readGate.complete(Unit)
            cleanupViewModel?.let { cancelAndJoin(it) }
        }
    }

    @Test
    fun readFailurePublishesErrorWithoutWritesAndRetryPublishesRealValues() = runTest {
        every { secureStorage.publicApiKey } throws IllegalStateException("read failed")
        val viewModel = newViewModel()
        try {
            assertTrue(awaitInitialization(viewModel) is SettingsInitializationState.Error)
            verify(exactly = 0) { secureStorage.publicApiKey = any() }
            verify(exactly = 0) { secureStorage.saveUserProfile(any(), any(), any()) }

            every { secureStorage.publicApiKey } returns "recovered-key"
            every { secureStorage.publicBaseUrl } returns "https://recovered.example"
            every { secureStorage.publicModel } returns "recovered-model"
            viewModel.retryInitialization()
            assertEquals(SettingsInitializationState.Ready, awaitInitialization(viewModel))
            assertEquals("recovered-key", viewModel.apiKey.value)
            assertEquals("https://recovered.example", viewModel.baseUrl.value)
            assertEquals("recovered-model", viewModel.model.value)
        } finally {
            cancelAndJoin(viewModel)
        }
    }

    @Test
    fun cancellationDuringIoReadLeavesLoadingAndDoesNotWriteDefaults() = runTest {
        val readGate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val readExited = CompletableDeferred<Unit>()
        var cleanupViewModel: SettingsViewModel? = null
        try {
            every { secureStorage.publicApiKey } answers {
                entered.complete(Unit)
                runBlocking {
                    kotlinx.coroutines.withTimeout(5_000) { readGate.await() }
                }
                readExited.complete(Unit)
                "value-after-cancel"
            }
            val viewModel = newViewModel()
            cleanupViewModel = viewModel
            runCurrent()
            awaitWall { entered.await() }
            viewModel.viewModelScope.cancel()
            readGate.complete(Unit)
            awaitWall { readExited.await() }
            awaitWall { viewModel.viewModelScope.coroutineContext[Job]!!.join() }
            runCurrent()

            assertEquals(SettingsInitializationState.Loading, viewModel.initialization.value)
            assertFalse(viewModel.initialization.value is SettingsInitializationState.Ready)
            verify(exactly = 0) { secureStorage.publicApiKey = any() }
            verify(exactly = 0) { secureStorage.saveUserProfile(any(), any(), any()) }
            verify(exactly = 0) { secureStorage.saveModelPlatform(any(), any()) }
        } finally {
            readGate.complete(Unit)
            cleanupViewModel?.let { cancelAndJoin(it) }
        }
    }

    @Test
    fun platformCommitFailureRetainsOldVisibleFlowAndRetryCommits() = runTest {
        val viewModel = newViewModel()
        try {
            assertEquals(SettingsInitializationState.Ready, awaitInitialization(viewModel))
            val old = ModelPlatform("old", "旧平台", "https://old.test", "old-key", listOf("old-model"), selectedModel = "old-model")
            val replacement = ModelPlatform("new", "新平台", "https://new.test", "new-key", listOf("new-model"), selectedModel = "new-model")

            viewModel.savePlatform(old, makeDefault = true)
            every { secureStorage.saveModelPlatform(replacement, true) } throws IllegalStateException("save failed")
            assertTrue(runCatching { viewModel.savePlatform(replacement, makeDefault = true) }.isFailure)
            assertEquals("old", viewModel.activePlatformId.value)
            assertEquals("old-key", viewModel.apiKey.value)
            assertEquals("https://old.test", viewModel.baseUrl.value)
            assertEquals("old-model", viewModel.model.value)

            every { secureStorage.saveModelPlatform(replacement, true) } returns Unit
            viewModel.savePlatform(replacement, makeDefault = true)
            assertEquals("new", viewModel.activePlatformId.value)
            assertEquals("new-key", viewModel.apiKey.value)
        } finally {
            cancelAndJoin(viewModel)
        }
    }

    @Test
    fun profileCommitFailureRetainsOldFlowAndRetryCommits() = runTest {
        every { secureStorage.userName } returns "旧名字"
        every { secureStorage.userDescription } returns "旧设定"
        every { secureStorage.userAvatarColor } returns "#111111"
        val viewModel = newViewModel()
        try {
            assertEquals(SettingsInitializationState.Ready, awaitInitialization(viewModel))

            every { secureStorage.saveUserProfile(any(), any(), any()) } throws IllegalStateException("disk unavailable")
            viewModel.updateProfile(" 新名字 ", "新设定", "#222222")
            awaitWall { viewModel.profileSaveError.first { it != null } }
            awaitWall { viewModel.profileSaving.first { !it } }
            assertEquals("旧名字", viewModel.userName.value)
            assertEquals("旧设定", viewModel.userDescription.value)
            assertEquals("#111111", viewModel.userAvatarColor.value)

            every { secureStorage.saveUserProfile("新名字", "新设定", "#222222") } returns Unit
            val saved = CompletableDeferred<Unit>()
            viewModel.updateProfile(" 新名字 ", "新设定", "#222222") { saved.complete(Unit) }
            awaitWall { saved.await() }
            assertEquals("新名字", viewModel.userName.value)
            assertEquals("新设定", viewModel.userDescription.value)
            assertEquals("#222222", viewModel.userAvatarColor.value)
        } finally {
            cancelAndJoin(viewModel)
        }
    }

    private fun newViewModel(): SettingsViewModel = SettingsViewModel(
        secureStorage,
        costRecordDao,
        systemProbeApi,
        uiPreferencesRepository,
        worldTemplateDao,
        encyclopediaDao,
    )

    private suspend fun awaitInitialization(viewModel: SettingsViewModel): SettingsInitializationState =
        awaitWall {
            viewModel.initialization.first {
                it is SettingsInitializationState.Ready || it is SettingsInitializationState.Error
            }
        }

    private suspend fun <T> awaitWall(block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { block() }
        }

    private suspend fun cancelAndJoin(viewModel: SettingsViewModel) {
        val ownerJob = viewModel.viewModelScope.coroutineContext[Job] ?: return
        viewModel.viewModelScope.cancel()
        awaitWall { ownerJob.join() }
    }
}
