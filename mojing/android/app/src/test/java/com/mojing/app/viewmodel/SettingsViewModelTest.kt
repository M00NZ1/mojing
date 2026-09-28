package com.mojing.app.viewmodel

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.ModelUsageSummary
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.dao.DefaultWorldOption
import com.mojing.app.data.local.dao.EncyclopediaFilterOption
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.remote.BackendSystemProbeApi
import com.mojing.app.ui.settings.SettingsViewModel
import com.mojing.app.ui.settings.CreationDefaultOption
import io.mockk.coEvery
import io.mockk.coVerify
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
    fun memoryIntervalOnlyPersistsEffectiveRange() = runTest {
        viewModel.updateMemoryCompactThreshold("2500")
        assertEquals("2500", viewModel.memoryCompactThreshold.value)
        verify(exactly = 0) { secureStorage.memoryCompactThreshold = any() }

        viewModel.updateMemoryCompactThreshold("120")
        verify(exactly = 1) { secureStorage.memoryCompactThreshold = 120 }
        viewModel.updateMemoryCompactThreshold("")
        verify(exactly = 1) { secureStorage.memoryCompactThreshold = any() }
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
    fun defaultsReadOnlySelectedLabelsAndPickerPages() = runTest(dispatcher) {
        viewModel.updateDefaultWorldTemplateId("wuxia")
        viewModel.updateDefaultEncyclopediaIdForAi("2")
        coEvery { worldTemplateDao.getDefaultWorldByTemplateId("wuxia") } returns
            DefaultWorldOption(1, "wuxia", "江湖", 3, 20)
        coEvery { encyclopediaDao.getNameById(2) } returns "门派志"
        val worlds = (1L..41L).map { DefaultWorldOption(it, "world-$it", "世界 $it", 0, 42 - it) }
        val encyclopedias = (1L..41L).map { EncyclopediaFilterOption(it, "百科 $it", 0, 42 - it) }
        coEvery { worldTemplateDao.getDefaultWorldPage(any(), any(), any(), any(), any()) } returns worlds
        coEvery { encyclopediaDao.getCharacterFilterPage(any(), any(), any(), any(), any(), any()) } returns encyclopedias

        viewModel.loadSelectedCreationDefaults()
        advanceUntilIdle()
        assertEquals("江湖", viewModel.creationDefaultLabels.value.world)
        assertEquals("门派志", viewModel.creationDefaultLabels.value.encyclopedia)
        assertFalse(viewModel.creationDefaultLabels.value.loading)
        val firstWorldPage = viewModel.loadDefaultWorldPage(" 世界 ", null)
        val firstEncyclopediaPage = viewModel.loadDefaultEncyclopediaPage(" 百科 ", null)
        assertEquals(40, firstWorldPage.rows.size)
        assertTrue(firstWorldPage.hasMore)
        assertEquals(40, firstEncyclopediaPage.rows.size)
        assertTrue(firstEncyclopediaPage.hasMore)
        viewModel.loadDefaultWorldPage("", firstWorldPage.rows.last())
        viewModel.loadDefaultEncyclopediaPage("", firstEncyclopediaPage.rows.last())
        coVerify { worldTemplateDao.getDefaultWorldPage("世界", null, null, null, 41) }
        coVerify { worldTemplateDao.getDefaultWorldPage("", 0, 2, 40, 41) }
        coVerify { encyclopediaDao.getCharacterFilterPage("百科", null, null, null, null, 41) }
        coVerify { encyclopediaDao.getCharacterFilterPage("", 0, 0, 2, 40, 41) }
        coVerify(exactly = 0) { worldTemplateDao.getAll() }
        coVerify(exactly = 0) { encyclopediaDao.getAll() }
    }

    @Test
    fun selectedDefaultReadFailureKeepsKeysAndAllowsRetry() = runTest(dispatcher) {
        viewModel.updateDefaultWorldTemplateId("wuxia")
        viewModel.updateDefaultEncyclopediaIdForAi("2")
        coEvery { worldTemplateDao.getDefaultWorldByTemplateId("wuxia") } returns
            DefaultWorldOption(1, "wuxia", "江湖", 0, 20)
        coEvery { encyclopediaDao.getNameById(2) } returns "门派志"
        viewModel.loadSelectedCreationDefaults()
        advanceUntilIdle()
        coEvery { worldTemplateDao.getDefaultWorldByTemplateId("wuxia") } throws IllegalStateException("database unavailable")
        coEvery { encyclopediaDao.getNameById(2) } returns null
        viewModel.loadSelectedCreationDefaults()
        advanceUntilIdle()
        assertEquals("江湖", viewModel.creationDefaultLabels.value.world)
        assertEquals("wuxia", viewModel.defaultWorldTemplateId.value)
        assertTrue(viewModel.creationDefaultLabels.value.encyclopediaMissing)
        assertEquals("已选默认资料暂时无法读取，请重试", viewModel.creationDefaultLabels.value.error)
        coEvery { worldTemplateDao.getDefaultWorldByTemplateId("wuxia") } returns null
        viewModel.loadSelectedCreationDefaults()
        advanceUntilIdle()
        assertTrue(viewModel.creationDefaultLabels.value.worldMissing)
        assertNull(viewModel.creationDefaultLabels.value.error)
        assertEquals("wuxia", viewModel.defaultWorldTemplateId.value)
    }

    @Test
    fun choosingDefaultsPersistsKeysWithoutLoadingWholeLibraries() = runTest(dispatcher) {
        viewModel.selectDefaultWorld(CreationDefaultOption("wuxia", "江湖", 1, 0, 1))
        viewModel.selectDefaultEncyclopedia(CreationDefaultOption("2", "门派志", 2, 0, 1))
        assertEquals("江湖", viewModel.creationDefaultLabels.value.world)
        assertEquals("门派志", viewModel.creationDefaultLabels.value.encyclopedia)
        verify { secureStorage.defaultWorldTemplateId = "wuxia" }
        verify { secureStorage.defaultEncyclopediaIdForAi = 2L }
        viewModel.selectDefaultWorld(null)
        viewModel.selectDefaultEncyclopedia(null)
        verify { secureStorage.defaultWorldTemplateId = "custom" }
        verify { secureStorage.defaultEncyclopediaIdForAi = 0L }
        coVerify(exactly = 0) { worldTemplateDao.getAll() }
        coVerify(exactly = 0) { encyclopediaDao.getAll() }
    }

}
