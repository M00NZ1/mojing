package com.mojing.app.viewmodel

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.ModelUsageSummary
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.remote.BackendSystemProbeApi
import com.mojing.app.ui.settings.SettingsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val costRecordDao = mockk<CostRecordDao>(relaxed = true)
    private val systemProbeApi = mockk<BackendSystemProbeApi>(relaxed = true)
    private val uiPreferencesRepository = mockk<UiPreferencesRepository>(relaxed = true) {
        every { chatDensity } returns flowOf("comfortable")
    }
    private val worldTemplateDao = mockk<WorldTemplateDao>(relaxed = true)
    private val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SettingsViewModel(
            secureStorage,
            costRecordDao,
            systemProbeApi,
            uiPreferencesRepository,
            worldTemplateDao,
            encyclopediaDao,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialApiKeyIsEmpty() = runTest {
        assertEquals("", viewModel.apiKey.value)
    }

    @Test
    fun updateApiKey() = runTest {
        viewModel.updateApiKey("sk-test-key")
        assertEquals("sk-test-key", viewModel.apiKey.value)
    }

    @Test
    fun updateBaseUrl() = runTest {
        viewModel.updateBaseUrl("https://api.openai.com")
        assertEquals("https://api.openai.com", viewModel.baseUrl.value)
    }

    @Test
    fun updateModel() = runTest {
        viewModel.updateModel("gpt-4o")
        assertEquals("gpt-4o", viewModel.model.value)
    }

    @Test
    fun savingNonDefaultPlatformDoesNotSwitchActiveCredentials() = runTest {
        val platform = ModelPlatform(
            id = "secondary",
            name = "备用平台",
            baseUrl = "https://secondary.test/v1",
            apiKey = "secondary-key",
            models = listOf("secondary-model"),
            selectedModel = "secondary-model",
        )

        viewModel.savePlatform(platform)

        assertEquals("", viewModel.activePlatformId.value)
        assertEquals("", viewModel.apiKey.value)
        verify { secureStorage.saveModelPlatform(platform, makeDefault = false) }
    }

    @Test
    fun updateProfilePublishesAndPersistsPersonaFields() = runTest {
        viewModel.updateProfile("沈砚", "来自雾都的调查员", "#334455")
        viewModel.profileSaving.first { !it }

        assertEquals("沈砚", viewModel.userName.value)
        assertEquals("来自雾都的调查员", viewModel.userDescription.value)
        assertEquals("#334455", viewModel.userAvatarColor.value)
        verify(exactly = 1) { secureStorage.saveUserProfile("沈砚", "来自雾都的调查员", "#334455") }
        verify(exactly = 0) { secureStorage.userAvatarImagePath = any() }
    }

    @Test
    fun profileFailureRetainsSavedValuesAndAllowsRetry() = runTest {
        val before = listOf(viewModel.userName.value, viewModel.userDescription.value, viewModel.userAvatarColor.value)
        var saved = false
        every { secureStorage.saveUserProfile(any(), any(), any()) } throws IllegalStateException("disk unavailable")
        viewModel.updateProfile(" 新名字 ", "新设定", "#112233") { saved = true }
        assertTrue(viewModel.profileSaving.value)
        viewModel.updateProfile("重复", "", "#000000")
        viewModel.profileSaving.first { !it }
        assertFalse(saved)
        assertEquals(before, listOf(viewModel.userName.value, viewModel.userDescription.value, viewModel.userAvatarColor.value))
        assertEquals("资料未保存，请重试", viewModel.profileSaveError.value)
        verify(exactly = 1) { secureStorage.saveUserProfile(any(), any(), any()) }
        every { secureStorage.saveUserProfile(any(), any(), any()) } returns Unit
        viewModel.updateProfile(" 新名字 ", "新设定", "#112233") { saved = true }
        viewModel.profileSaving.first { !it }
        assertTrue(saved)
        assertNull(viewModel.profileSaveError.value)
        assertEquals("新名字", viewModel.userName.value)
        assertEquals("新设定", viewModel.userDescription.value)
    }

    @Test
    fun updateUserAvatarImagePathAppliesImmediately() = runTest {
        viewModel.updateUserAvatarImagePath("avatars/persona.png")

        assertEquals("avatars/persona.png", viewModel.userAvatarImagePath.value)
        verify { secureStorage.userAvatarImagePath = "avatars/persona.png" }
    }

    @Test
    fun loadCreationOptionsPublishesAvailableWorldsAndEncyclopedias() = runTest(dispatcher) {
        val worlds = listOf(WorldTemplateEntity(id = 1, templateId = "wuxia", label = "江湖"))
        val encyclopedias = listOf(EncyclopediaEntity(id = 2, name = "门派志"))
        coEvery { worldTemplateDao.getAll() } returns worlds
        coEvery { encyclopediaDao.getAll() } returns encyclopedias

        viewModel.loadCreationOptions()
        advanceUntilIdle()

        assertEquals(worlds, viewModel.availableWorldTemplates.value)
        assertEquals(encyclopedias, viewModel.availableEncyclopedias.value)
        assertFalse(viewModel.creationOptionsLoading.value)
        assertNull(viewModel.creationOptionsError.value)
    }

    @Test
    fun loadCreationOptionsKeepsLastSuccessfulListWhenRefreshFails() = runTest(dispatcher) {
        val worlds = listOf(WorldTemplateEntity(id = 1, templateId = "wuxia", label = "江湖"))
        val encyclopedias = listOf(EncyclopediaEntity(id = 2, name = "门派志"))
        coEvery { worldTemplateDao.getAll() } returns worlds
        coEvery { encyclopediaDao.getAll() } returns encyclopedias
        viewModel.loadCreationOptions()
        advanceUntilIdle()

        coEvery { worldTemplateDao.getAll() } throws IllegalStateException("database unavailable")
        coEvery { encyclopediaDao.getAll() } returns emptyList()
        viewModel.loadCreationOptions()
        advanceUntilIdle()

        assertEquals(worlds, viewModel.availableWorldTemplates.value)
        assertEquals(emptyList<EncyclopediaEntity>(), viewModel.availableEncyclopedias.value)
        assertEquals("世界列表读取失败，请重试", viewModel.creationOptionsError.value)
        assertFalse(viewModel.creationOptionsLoading.value)
    }

}
