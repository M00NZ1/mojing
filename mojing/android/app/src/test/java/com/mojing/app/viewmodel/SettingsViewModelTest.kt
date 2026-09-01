package com.mojing.app.viewmodel

import com.mojing.app.data.SecureStorage
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
    fun updateProfilePublishesAndPersistsPersonaFields() = runTest {
        viewModel.updateProfile("沈砚", "来自雾都的调查员", "#334455")

        assertEquals("沈砚", viewModel.userName.value)
        assertEquals("来自雾都的调查员", viewModel.userDescription.value)
        assertEquals("#334455", viewModel.userAvatarColor.value)
        verify { secureStorage.userName = "沈砚" }
        verify { secureStorage.userDescription = "来自雾都的调查员" }
        verify { secureStorage.userAvatarColor = "#334455" }
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

    @Test
    fun loadCostStatsPublishesTotalsAndModelUsage() = runTest(dispatcher) {
        val summaries = listOf(
            ModelUsageSummary(
                modelName = "gpt-4o-mini",
                totalCalls = 3,
                failedCalls = 1,
                totalTokens = 4_200,
                estimatedCost = 0.0123,
            ),
        )
        coEvery { costRecordDao.getTotalCost() } returns 0.0123
        coEvery { costRecordDao.getTotalTokens() } returns 4_200
        coEvery { costRecordDao.getTotalCalls() } returns 3
        coEvery { costRecordDao.getFailedCalls() } returns 1
        coEvery { costRecordDao.getModelUsageSummaries() } returns summaries

        viewModel.loadCostStats()
        advanceUntilIdle()

        assertEquals(0.0123, viewModel.totalCost.value, 0.0)
        assertEquals(4_200, viewModel.totalTokens.value)
        assertEquals(3, viewModel.totalCalls.value)
        assertEquals(1, viewModel.failedCalls.value)
        assertEquals(summaries, viewModel.modelUsage.value)
        assertNull(viewModel.costStatsError.value)
        assertFalse(viewModel.costStatsLoading.value)
    }

    @Test
    fun loadCostStatsKeepsLastSnapshotWhenRefreshFails() = runTest(dispatcher) {
        val summaries = listOf(ModelUsageSummary("gpt-4o-mini", 2, 0, 1_000, 0.004))
        coEvery { costRecordDao.getTotalCost() } returns 0.004
        coEvery { costRecordDao.getTotalTokens() } returns 1_000
        coEvery { costRecordDao.getTotalCalls() } returns 2
        coEvery { costRecordDao.getFailedCalls() } returns 0
        coEvery { costRecordDao.getModelUsageSummaries() } returns summaries
        viewModel.loadCostStats()
        advanceUntilIdle()

        coEvery { costRecordDao.getTotalCost() } throws IllegalStateException("database unavailable")
        viewModel.loadCostStats()
        advanceUntilIdle()

        assertEquals(0.004, viewModel.totalCost.value, 0.0)
        assertEquals(2, viewModel.totalCalls.value)
        assertEquals(summaries, viewModel.modelUsage.value)
        assertEquals("本机用量记录读取失败，请重试", viewModel.costStatsError.value)
        assertFalse(viewModel.costStatsLoading.value)
    }
}
